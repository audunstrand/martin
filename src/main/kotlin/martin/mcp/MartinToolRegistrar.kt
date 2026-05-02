package martin.mcp

import kotlinx.serialization.json.*
import martin.compiler.AnalysisResult
import martin.compiler.GradleProjectDiscovery
import martin.compiler.KotlinAnalyzer
import martin.refactoring.*
import martin.refactoring.convert.*
import martin.refactoring.core.*
import martin.refactoring.core.InlineRefactoring.SourceLocation
import martin.refactoring.extract.*
import martin.refactoring.restructure.*
import martin.rewriter.SourceRewriter
import martin.rewriter.TextEdit
import java.nio.file.Path
import kotlin.io.path.Path

/**
 * Builds an McpToolRegistry with all 25 Martin refactoring tools registered.
 *
 * Each tool:
 * 1. Receives JSON arguments from the MCP client (coding agent)
 * 2. Runs the Kotlin compiler analysis
 * 3. Executes the refactoring
 * 4. Applies edits to disk
 * 5. Returns a structured text result
 */
class MartinToolRegistrar(
    private val projectDir: Path,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun register(registry: McpToolRegistry) {
        registerRename(registry)
        registerExtractFunction(registry)
        registerExtractVariable(registry)
        registerInline(registry)
        registerMove(registry)
        registerChangeSignature(registry)
        registerConvertToExpressionBody(registry)
        registerConvertToBlockBody(registry)
        registerAddNamedArguments(registry)
        registerExtractConstant(registry)
        registerSafeDelete(registry)
        registerConvertPropertyToFunction(registry)
        registerExtractParameter(registry)
        registerIntroduceParameterObject(registry)
        registerExtractInterface(registry)
        registerExtractSuperclass(registry)
        registerPullUpMethod(registry)
        registerReplaceConstructorWithFactory(registry)
        registerConvertToDataClass(registry)
        registerConvertToExtensionFunction(registry)
        registerConvertToSealedClass(registry)
        registerEncapsulateField(registry)
        registerTypeMigration(registry)
        registerMoveStatementsIntoFunction(registry)
        registerSafeDeleteUnused(registry)
    }

    // Helper to run a refactoring with fresh analysis
    private inline fun withAnalysis(block: (AnalysisResult) -> List<TextEdit>): String {
        val analyzer = KotlinAnalyzer.create(projectDir)
        val analysis = analyzer.analyze()
        return try {
            val edits = block(analysis)
            val filesModified = if (edits.isNotEmpty()) SourceRewriter.applyEdits(edits) else 0
            "${edits.size} edits across $filesModified files"
        } finally {
            analysis.close()
        }
    }

    // Helper for refactorings that return RefactoringOutput (may create new files)
    private inline fun withAnalysisOutput(block: (AnalysisResult) -> martin.refactoring.RefactoringOutput): String {
        val analyzer = KotlinAnalyzer.create(projectDir)
        val analysis = analyzer.analyze()
        return try {
            val output = block(analysis)
            output.writeNewFiles()
            val edits = output.edits
            val filesModified = if (edits.isNotEmpty()) SourceRewriter.applyEdits(edits) else 0
            val totalFiles = filesModified + output.newFiles.size
            "${edits.size} edits across $totalFiles files"
        } finally {
            analysis.close()
        }
    }

    private fun JsonObject.str(key: String): String =
        this[key]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing required argument: $key")

    private fun JsonObject.int(key: String): Int =
        this[key]?.jsonPrimitive?.int ?: throw IllegalArgumentException("Missing required argument: $key")

    private fun JsonObject.strOrNull(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.intOrNull(key: String): Int? =
        this[key]?.jsonPrimitive?.intOrNull

    private fun filePath(args: JsonObject): Path {
        val raw = args.str("file")
        val path = Path(raw)
        return if (path.isAbsolute) path else projectDir.resolve(path)
    }

    // ---- Schema builders ----

    private fun schema(vararg props: Pair<String, JsonObject>, required: List<String>? = null): JsonObject {
        return buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                for ((name, schema) in props) {
                    put(name, schema)
                }
            }
            val req = required ?: props.map { it.first }
            put("required", buildJsonArray { req.forEach { add(it) } })
        }
    }

    private fun stringProp(description: String) = buildJsonObject {
        put("type", "string"); put("description", description)
    }

    private fun intProp(description: String) = buildJsonObject {
        put("type", "integer"); put("description", description)
    }

    // ---- File + line + col schema (most common pattern) ----

    private val fileLineColSchema = arrayOf(
        "file" to stringProp("Path to the Kotlin source file (relative to project root or absolute)"),
        "line" to intProp("1-based line number"),
        "col" to intProp("1-based column number"),
    )

    // ---- Tool registrations ----

    private fun registerRename(r: McpToolRegistry) = r.addTool(
        name = "rename",
        description = "Rename a symbol (function, class, variable, parameter) and update all references across the project",
        inputSchema = schema(
            *fileLineColSchema,
            "newName" to stringProp("The new name for the symbol"),
        ),
    ) { args ->
        withAnalysis { analysis ->
            RenameRefactoring(analysis).rename(filePath(args), args.int("line"), args.int("col"), args.str("newName"))
        }
    }

    private fun registerExtractFunction(r: McpToolRegistry) = r.addTool(
        name = "extract-function",
        description = "Extract a range of lines into a new function. Parameters and return values are inferred automatically",
        inputSchema = schema(
            "file" to stringProp("Path to the Kotlin source file"),
            "startLine" to intProp("First line to extract (1-based)"),
            "endLine" to intProp("Last line to extract (1-based)"),
            "name" to stringProp("Name for the new function"),
        ),
    ) { args ->
        withAnalysis { analysis ->
            ExtractFunctionRefactoring(analysis).extract(filePath(args), args.int("startLine"), args.int("endLine"), args.str("name"))
        }
    }

    private fun registerExtractVariable(r: McpToolRegistry) = r.addTool(
        name = "extract-variable",
        description = "Extract the expression at the cursor into a named val. The expression is replaced with the variable name",
        inputSchema = schema(
            *fileLineColSchema,
            "name" to stringProp("Name for the new variable"),
        ),
    ) { args ->
        withAnalysis { analysis ->
            ExtractVariableRefactoring(analysis).extract(filePath(args), args.int("line"), args.int("col"), args.str("name"))
        }
    }

    private fun registerInline(r: McpToolRegistry) = r.addTool(
        name = "inline",
        description = "Inline a variable or function at the cursor. Replaces all usages with the initializer/body and removes the declaration",
        inputSchema = schema(*fileLineColSchema),
    ) { args ->
        withAnalysis { analysis ->
            InlineRefactoring(analysis).inline(SourceLocation(filePath(args), args.int("line"), args.int("col")))
        }
    }

    private fun registerMove(r: McpToolRegistry) = r.addTool(
        name = "move",
        description = "Move a top-level declaration to a different package. Updates all imports across the project",
        inputSchema = schema(
            "symbol" to stringProp("Fully qualified name of the symbol to move (e.g. 'com.example.MyClass')"),
            "toPackage" to stringProp("Target package name (e.g. 'com.other')"),
        ),
    ) { args ->
        withAnalysisOutput { analysis ->
            val sourceRoots = GradleProjectDiscovery(projectDir).discoverSourceRoots()
            MoveRefactoring(analysis).move(args.str("symbol"), args.str("toPackage"), sourceRoots)
        }
    }

    private fun registerChangeSignature(r: McpToolRegistry) = r.addTool(
        name = "change-signature",
        description = "Change a function's parameter list. Supports adding, removing, reordering, and renaming parameters. Updates all call sites",
        inputSchema = schema(
            *fileLineColSchema,
            "params" to stringProp("New parameter list as comma-separated 'name:Type' or 'name:Type=default' entries"),
        ),
    ) { args ->
        withAnalysis { analysis ->
            val params = parseParameterSpecs(args.str("params"))
            ChangeSignatureRefactoring(analysis).changeSignature(filePath(args), args.int("line"), args.int("col"), params)
        }
    }

    private fun registerConvertToExpressionBody(r: McpToolRegistry) = r.addTool(
        name = "convert-to-expression-body",
        description = "Convert a function with a block body containing a single return to an expression body (= syntax)",
        inputSchema = schema(*fileLineColSchema),
    ) { args ->
        withAnalysis { analysis ->
            ConvertToExpressionBodyRefactoring(analysis).convert(filePath(args), args.int("line"), args.int("col"))
        }
    }

    private fun registerConvertToBlockBody(r: McpToolRegistry) = r.addTool(
        name = "convert-to-block-body",
        description = "Convert a function with an expression body (= syntax) to a block body with explicit return",
        inputSchema = schema(*fileLineColSchema),
    ) { args ->
        withAnalysis { analysis ->
            ConvertToBlockBodyRefactoring(analysis).convert(filePath(args), args.int("line"), args.int("col"))
        }
    }

    private fun registerAddNamedArguments(r: McpToolRegistry) = r.addTool(
        name = "add-named-arguments",
        description = "Add explicit parameter names to all arguments of a function call at the cursor",
        inputSchema = schema(*fileLineColSchema),
    ) { args ->
        withAnalysis { analysis ->
            AddNamedArgumentsRefactoring(analysis).convert(filePath(args), args.int("line"), args.int("col"))
        }
    }

    private fun registerExtractConstant(r: McpToolRegistry) = r.addTool(
        name = "extract-constant",
        description = "Extract a literal value at the cursor into a named const val. Places it in a companion object if inside a class",
        inputSchema = schema(
            *fileLineColSchema,
            "name" to stringProp("Name for the constant (UPPER_SNAKE_CASE recommended)"),
        ),
    ) { args ->
        withAnalysis { analysis ->
            ExtractConstantRefactoring(analysis).extract(filePath(args), args.int("line"), args.int("col"), args.str("name"))
        }
    }

    private fun registerSafeDelete(r: McpToolRegistry) = r.addTool(
        name = "safe-delete",
        description = "Delete a declaration only if it has no usages. Fails with a list of usages if any are found",
        inputSchema = schema(*fileLineColSchema),
    ) { args ->
        withAnalysis { analysis ->
            SafeDeleteRefactoring(analysis).delete(filePath(args), args.int("line"), args.int("col"))
        }
    }

    private fun registerConvertPropertyToFunction(r: McpToolRegistry) = r.addTool(
        name = "convert-property-to-function",
        description = "Convert a property with a getter to a function. Updates all usage sites",
        inputSchema = schema(*fileLineColSchema),
    ) { args ->
        withAnalysis { analysis ->
            ConvertPropertyToFunctionRefactoring(analysis).convert(filePath(args), args.int("line"), args.int("col"))
        }
    }

    private fun registerExtractParameter(r: McpToolRegistry) = r.addTool(
        name = "extract-parameter",
        description = "Extract an expression inside a function body into a new parameter. Existing call sites pass the expression as an argument",
        inputSchema = schema(
            *fileLineColSchema,
            "name" to stringProp("Name for the new parameter"),
        ),
    ) { args ->
        withAnalysis { analysis ->
            ExtractParameterRefactoring(analysis).extract(filePath(args), args.int("line"), args.int("col"), args.str("name"))
        }
    }

    private fun registerIntroduceParameterObject(r: McpToolRegistry) = r.addTool(
        name = "introduce-parameter-object",
        description = "Group several parameters of a function into a data class. Updates the function signature and all call sites",
        inputSchema = schema(
            *fileLineColSchema,
            "className" to stringProp("Name for the new data class"),
            "paramNames" to stringProp("Comma-separated list of parameter names to group"),
        ),
    ) { args ->
        val paramNames = args.str("paramNames").split(",").map { it.trim() }
        withAnalysis { analysis ->
            IntroduceParameterObjectRefactoring(analysis).introduce(filePath(args), args.int("line"), args.int("col"), args.str("className"), paramNames)
        }
    }

    private fun registerExtractInterface(r: McpToolRegistry) = r.addTool(
        name = "extract-interface",
        description = "Extract an interface from a class, moving selected methods to the interface. The class implements the new interface",
        inputSchema = schema(
            *fileLineColSchema,
            "interfaceName" to stringProp("Name for the new interface"),
            "methods" to stringProp("Comma-separated list of method names to include in the interface"),
        ),
    ) { args ->
        val methods = args.str("methods").split(",").map { it.trim() }
        withAnalysisOutput { analysis ->
            ExtractInterfaceRefactoring(analysis).extract(filePath(args), args.int("line"), args.int("col"), args.str("interfaceName"), methods)
        }
    }

    private fun registerExtractSuperclass(r: McpToolRegistry) = r.addTool(
        name = "extract-superclass",
        description = "Extract a superclass from a class, moving selected members up. The original class extends the new superclass",
        inputSchema = schema(
            *fileLineColSchema,
            "superclassName" to stringProp("Name for the new superclass"),
            "members" to stringProp("Comma-separated list of member names to move to the superclass"),
        ),
    ) { args ->
        val members = args.str("members").split(",").map { it.trim() }
        withAnalysisOutput { analysis ->
            ExtractSuperclassRefactoring(analysis).extract(filePath(args), args.int("line"), args.int("col"), args.str("superclassName"), members)
        }
    }

    private fun registerPullUpMethod(r: McpToolRegistry) = r.addTool(
        name = "pull-up-method",
        description = "Move a method from a subclass to its superclass",
        inputSchema = schema(*fileLineColSchema),
    ) { args ->
        withAnalysis { analysis ->
            PullUpMethodRefactoring(analysis).pullUp(filePath(args), args.int("line"), args.int("col"))
        }
    }

    private fun registerReplaceConstructorWithFactory(r: McpToolRegistry) = r.addTool(
        name = "replace-constructor-with-factory",
        description = "Replace direct constructor calls with a factory method. Makes the constructor private and adds a companion object factory",
        inputSchema = schema(
            *fileLineColSchema,
            "factoryName" to stringProp("Name for the factory method (default: 'create')"),
            required = listOf("file", "line", "col"),
        ),
    ) { args ->
        val factoryName = args.strOrNull("factoryName") ?: "create"
        withAnalysis { analysis ->
            ReplaceConstructorWithFactoryRefactoring(analysis).replace(filePath(args), args.int("line"), args.int("col"), factoryName)
        }
    }

    private fun registerConvertToDataClass(r: McpToolRegistry) = r.addTool(
        name = "convert-to-data-class",
        description = "Convert a regular class to a data class. Adds the 'data' modifier and generates equals/hashCode/toString/copy",
        inputSchema = schema(*fileLineColSchema),
    ) { args ->
        withAnalysis { analysis ->
            ConvertToDataClassRefactoring(analysis).convert(filePath(args), args.int("line"), args.int("col"))
        }
    }

    private fun registerConvertToExtensionFunction(r: McpToolRegistry) = r.addTool(
        name = "convert-to-extension-function",
        description = "Convert a function's first parameter to a receiver, making it an extension function",
        inputSchema = schema(*fileLineColSchema),
    ) { args ->
        withAnalysis { analysis ->
            ConvertToExtensionFunctionRefactoring(analysis).convert(filePath(args), args.int("line"), args.int("col"))
        }
    }

    private fun registerConvertToSealedClass(r: McpToolRegistry) = r.addTool(
        name = "convert-to-sealed-class",
        description = "Convert an interface or abstract class to a sealed class/interface",
        inputSchema = schema(*fileLineColSchema),
    ) { args ->
        withAnalysis { analysis ->
            ConvertToSealedClassRefactoring(analysis).convert(filePath(args), args.int("line"), args.int("col"))
        }
    }

    private fun registerEncapsulateField(r: McpToolRegistry) = r.addTool(
        name = "encapsulate-field",
        description = "Make a public property private and generate getter/setter methods. Updates all usage sites",
        inputSchema = schema(*fileLineColSchema),
    ) { args ->
        withAnalysis { analysis ->
            EncapsulateFieldRefactoring(analysis).encapsulate(filePath(args), args.int("line"), args.int("col"))
        }
    }

    private fun registerTypeMigration(r: McpToolRegistry) = r.addTool(
        name = "type-migration",
        description = "Change the type of a variable/property/parameter and update all related type annotations",
        inputSchema = schema(
            *fileLineColSchema,
            "newType" to stringProp("The new type to migrate to (e.g. 'Long', 'List<String>')"),
        ),
    ) { args ->
        withAnalysis { analysis ->
            TypeMigrationRefactoring(analysis).migrate(filePath(args), args.int("line"), args.int("col"), args.str("newType"))
        }
    }

    private fun registerMoveStatementsIntoFunction(r: McpToolRegistry) = r.addTool(
        name = "move-statements-into-function",
        description = "Move a range of statements into an existing function body",
        inputSchema = schema(
            "file" to stringProp("Path to the Kotlin source file"),
            "functionLine" to intProp("Line of the target function declaration"),
            "functionCol" to intProp("Column of the target function declaration"),
            "startLine" to intProp("First line of statements to move"),
            "endLine" to intProp("Last line of statements to move"),
        ),
    ) { args ->
        withAnalysis { analysis ->
            MoveStatementsIntoFunctionRefactoring(analysis).move(
                filePath(args), args.int("functionLine"), args.int("functionCol"), args.int("startLine"), args.int("endLine")
            )
        }
    }

    // Bonus: a non-refactoring utility tool for agents
    private fun registerSafeDeleteUnused(r: McpToolRegistry) = r.addTool(
        name = "find-unused",
        description = "List all top-level declarations (functions, classes, properties) that have no usages in the project",
        inputSchema = schema(
            "file" to stringProp("Path to the Kotlin source file to scan"),
            required = listOf("file"),
        ),
    ) { args ->
        val analyzer = KotlinAnalyzer.create(projectDir)
        val analysis = analyzer.analyze()
        try {
            val ktFile = RefactoringUtils.findKtFile(analysis, filePath(args))
            val unused = mutableListOf<String>()
            for (decl in ktFile.declarations) {
                if (decl is org.jetbrains.kotlin.psi.KtNamedDeclaration) {
                    val descriptor = analysis.bindingContext[org.jetbrains.kotlin.resolve.BindingContext.DECLARATION_TO_DESCRIPTOR, decl]
                    if (descriptor != null) {
                        val refs = RefactoringUtils.findAllReferences(analysis, descriptor)
                        if (refs.isEmpty()) {
                            val name = decl.name ?: continue
                            val line = RefactoringUtils.offsetToLineCol(ktFile.text, decl.textOffset).first
                            unused.add("$name (line $line)")
                        }
                    }
                }
            }
            if (unused.isEmpty()) "No unused declarations found" else "Unused declarations:\n${unused.joinToString("\n") { "  - $it" }}"
        } finally {
            analysis.close()
        }
    }

    companion object {
        private fun parseParameterSpecs(raw: String): List<ChangeSignatureRefactoring.ParameterSpec> =
            raw.split(",").map { param ->
                val parts = param.trim().split(":")
                val name = parts[0].trim()
                val typeAndDefault = parts.getOrNull(1)?.trim() ?: throw IllegalArgumentException("Missing type for param '$name'")
                val type = if (typeAndDefault.contains("=")) typeAndDefault.substringBefore("=").trim() else typeAndDefault
                val default = if (param.contains("=")) param.substringAfter("=").trim() else null
                ChangeSignatureRefactoring.ParameterSpec(name, type, default)
            }
    }
}
