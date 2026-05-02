package martin.mcp

import kotlinx.serialization.json.*
import martin.compiler.KotlinAnalyzer
import martin.refactoring.*
import martin.rewriter.SourceRewriter
import java.nio.file.Path
import kotlin.io.path.Path

/**
 * Builds an McpToolRegistry with all Martin refactoring tools registered.
 *
 * Uses [RefactoringRegistry] to generate tools dynamically — each refactoring's
 * metadata (name, description, params) drives the MCP tool schema.
 */
class MartinToolRegistrar(
    private val projectDir: Path,
) {
    fun register(registry: McpToolRegistry) {
        for (entry in RefactoringRegistry.entries) {
            registerRefactoring(registry, entry)
        }
        registerFindUnused(registry)
    }

    private fun registerRefactoring(registry: McpToolRegistry, entry: RefactoringEntry) {
        // Build input schema from standard file/line/col + extra params
        val hasFileLineCol = entry.params.none { it.name in setOf("startLine", "endLine", "statementsStartLine", "statementsEndLine") }
            && entry.name != "move" // Move doesn't use file/line/col

        val props = mutableListOf<Pair<String, JsonObject>>()
        val required = mutableListOf<String>()

        if (entry.name != "move") {
            props.add("file" to stringProp("Path to the Kotlin source file (relative to project root or absolute)"))
            required.add("file")
        }
        if (hasFileLineCol) {
            props.add("line" to intProp("1-based line number"))
            props.add("col" to intProp("1-based column number"))
            required.add("line")
            required.add("col")
        }

        for (param in entry.params) {
            val prop = when (param.type) {
                ParamType.INT -> intProp(param.description)
                else -> stringProp(param.description)
            }
            props.add(param.name to prop)
            if (param.required) required.add(param.name)
        }

        val inputSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                for ((name, schema) in props) put(name, schema)
            }
            put("required", buildJsonArray { required.forEach { add(it) } })
        }

        registry.addTool(
            name = entry.name,
            description = entry.description,
            inputSchema = inputSchema,
        ) { args ->
            val analyzer = KotlinAnalyzer.create(projectDir)
            val analysis = analyzer.analyze()
            try {
                val refactoring = entry.factory(analysis)

                // Build args map from JSON
                val extraArgs = mutableMapOf<String, String>()
                for (param in entry.params) {
                    val value = args[param.name]?.jsonPrimitive?.content
                    if (value != null) extraArgs[param.name] = value
                }

                val file = args["file"]?.jsonPrimitive?.content?.let {
                    val path = Path(it)
                    if (path.isAbsolute) path else projectDir.resolve(path)
                } ?: projectDir

                val ctx = RefactoringContext(
                    analysis = analysis,
                    file = file,
                    line = args["line"]?.jsonPrimitive?.intOrNull ?: 0,
                    col = args["col"]?.jsonPrimitive?.intOrNull ?: 0,
                    args = extraArgs,
                    projectDir = projectDir,
                )

                val output = refactoring.execute(ctx)
                output.writeNewFiles()
                val edits = output.edits
                val filesModified = if (edits.isNotEmpty()) SourceRewriter.applyEdits(edits) else 0
                val totalFiles = filesModified + output.newFiles.size
                "${edits.size} edits across $totalFiles files"
            } finally {
                analysis.close()
            }
        }
    }

    // Bonus: a non-refactoring utility tool for agents
    private fun registerFindUnused(registry: McpToolRegistry) {
        registry.addTool(
            name = "find-unused",
            description = "List all top-level declarations that have no usages in the project",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    put("file", stringProp("Path to the Kotlin source file to scan"))
                }
                put("required", buildJsonArray { add("file") })
            },
        ) { args ->
            val analyzer = KotlinAnalyzer.create(projectDir)
            val analysis = analyzer.analyze()
            try {
                val rawPath = args["file"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing 'file'")
                val path = Path(rawPath).let { if (it.isAbsolute) it else projectDir.resolve(it) }
                val ktFile = RefactoringUtils.findKtFile(analysis, path)
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
    }

    private fun stringProp(description: String) = buildJsonObject {
        put("type", "string"); put("description", description)
    }

    private fun intProp(description: String) = buildJsonObject {
        put("type", "integer"); put("description", description)
    }
}
