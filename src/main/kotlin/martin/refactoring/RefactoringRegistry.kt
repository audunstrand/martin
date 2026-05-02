package martin.refactoring

import martin.compiler.AnalysisResult
import martin.refactoring.convert.*
import martin.refactoring.core.*
import martin.refactoring.extract.*
import martin.refactoring.restructure.*

/**
 * Metadata and factory for a single refactoring.
 */
data class RefactoringEntry(
    val name: String,
    val description: String,
    val params: List<ParamDef>,
    val factory: (AnalysisResult) -> Refactoring,
)

/**
 * Central registry of all available refactorings.
 *
 * Each entry provides metadata (name, description, params) and a factory
 * that creates the refactoring given an [AnalysisResult].
 * This is the single source of truth for what refactorings Martin supports.
 */
object RefactoringRegistry {

    val entries: List<RefactoringEntry> = listOf(
        // Core
        entry("rename", "Rename a symbol and update all references across the project",
            listOf(ParamDef("newName", ParamType.STRING, "The new name for the symbol")),
            ::RenameRefactoring),
        entry("inline", "Inline a variable or function at the cursor, replacing usages with the body",
            emptyList(), ::InlineRefactoring),
        entry("move", "Move a top-level declaration to a different package, updating all imports",
            listOf(
                ParamDef("symbol", ParamType.STRING, "Fully qualified name of the symbol to move"),
                ParamDef("toPackage", ParamType.STRING, "Target package name"),
                ParamDef("sourceRoots", ParamType.STRING, "Comma-separated source root paths", required = false),
            ), ::MoveRefactoring),
        entry("safe-delete", "Delete a declaration only if it has no usages",
            emptyList(), ::SafeDeleteRefactoring),
        entry("change-signature", "Change a function's parameter list and update all call sites",
            listOf(ParamDef("params", ParamType.STRING, "New parameter list as comma-separated 'name:Type' or 'name:Type=default' entries")),
            ::ChangeSignatureRefactoring),
        // Extract
        entry("extract-function", "Extract a range of lines into a new function with inferred parameters",
            listOf(
                ParamDef("startLine", ParamType.INT, "First line to extract (1-based)"),
                ParamDef("endLine", ParamType.INT, "Last line to extract (1-based)"),
                ParamDef("name", ParamType.STRING, "Name for the new function"),
            ), ::ExtractFunctionRefactoring),
        entry("extract-variable", "Extract the expression at the cursor into a named val",
            listOf(ParamDef("name", ParamType.STRING, "Name for the new variable")),
            ::ExtractVariableRefactoring),
        entry("extract-constant", "Extract a literal value into a named const val",
            listOf(ParamDef("name", ParamType.STRING, "Name for the constant")),
            ::ExtractConstantRefactoring),
        entry("extract-parameter", "Extract an expression inside a function body into a new parameter",
            listOf(ParamDef("name", ParamType.STRING, "Name for the new parameter")),
            ::ExtractParameterRefactoring),
        entry("extract-interface", "Extract an interface from a class with selected methods",
            listOf(
                ParamDef("interfaceName", ParamType.STRING, "Name for the new interface"),
                ParamDef("methods", ParamType.STRING_LIST, "Comma-separated list of method names to include"),
            ), ::ExtractInterfaceRefactoring),
        entry("extract-superclass", "Extract a superclass from a class with selected members",
            listOf(
                ParamDef("superclassName", ParamType.STRING, "Name for the new superclass"),
                ParamDef("members", ParamType.STRING_LIST, "Comma-separated list of member names"),
            ), ::ExtractSuperclassRefactoring),
        // Convert
        entry("convert-to-expression-body", "Convert a function with block body to expression body (= syntax)",
            emptyList(), ::ConvertToExpressionBodyRefactoring),
        entry("convert-to-block-body", "Convert a function with expression body to block body with explicit return",
            emptyList(), ::ConvertToBlockBodyRefactoring),
        entry("convert-to-data-class", "Convert a regular class to a data class",
            emptyList(), ::ConvertToDataClassRefactoring),
        entry("convert-to-sealed-class", "Convert an interface or abstract class to a sealed class/interface",
            emptyList(), ::ConvertToSealedClassRefactoring),
        entry("convert-to-extension-function", "Convert a function's first parameter to a receiver",
            emptyList(), ::ConvertToExtensionFunctionRefactoring),
        entry("convert-property-to-function", "Convert a property with a getter to a function",
            emptyList(), ::ConvertPropertyToFunctionRefactoring),
        entry("add-named-arguments", "Add explicit parameter names to all arguments of a function call",
            emptyList(), ::AddNamedArgumentsRefactoring),
        // Restructure
        entry("encapsulate-field", "Make a public property private and generate getter/setter methods",
            emptyList(), ::EncapsulateFieldRefactoring),
        entry("pull-up-method", "Move a method from a subclass to its superclass",
            emptyList(), ::PullUpMethodRefactoring),
        entry("replace-constructor-with-factory", "Replace constructor calls with a factory method",
            listOf(ParamDef("factoryName", ParamType.STRING, "Name for the factory method", required = false, default = "create")),
            ::ReplaceConstructorWithFactoryRefactoring),
        entry("introduce-parameter-object", "Group several function parameters into a data class",
            listOf(
                ParamDef("className", ParamType.STRING, "Name for the parameter object class"),
                ParamDef("paramNames", ParamType.STRING_LIST, "Comma-separated list of parameter names to group"),
            ), ::IntroduceParameterObjectRefactoring),
        entry("type-migration", "Change the type of a variable/property/parameter and update all type annotations",
            listOf(ParamDef("newType", ParamType.STRING, "The new type to migrate to")),
            ::TypeMigrationRefactoring),
        entry("move-statements-into-function", "Move a range of statements into an existing function body",
            listOf(
                ParamDef("statementsStartLine", ParamType.INT, "First line of statements to move"),
                ParamDef("statementsEndLine", ParamType.INT, "Last line of statements to move"),
            ), ::MoveStatementsIntoFunctionRefactoring),
    )

    /** Look up a refactoring entry by command name. */
    fun find(name: String): RefactoringEntry? = entries.find { it.name == name }

    private fun entry(
        name: String,
        description: String,
        params: List<ParamDef>,
        factory: (AnalysisResult) -> Refactoring,
    ) = RefactoringEntry(name, description, params, factory)
}
