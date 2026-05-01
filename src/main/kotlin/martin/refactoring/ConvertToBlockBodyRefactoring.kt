package martin.refactoring

import martin.compiler.AnalysisResult
import martin.rewriter.TextEdit
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import java.nio.file.Path

/**
 * Convert a function with expression body to block body.
 * `fun f(): T = expr` -> `fun f(): T { return expr }`
 * For Unit return type: `fun f() = expr` -> `fun f() { expr }`
 */
class ConvertToBlockBodyRefactoring(private val analysis: AnalysisResult) {

    fun convert(file: Path, line: Int, col: Int): List<TextEdit> {
        val (ktFile, element) = RefactoringUtils.findElementAt(analysis, file, line, col)
        val function = requireNotNull(RefactoringUtils.findParent<KtNamedFunction>(element)) { "No function found at $file:$line:$col" }

        require(!function.hasBlockBody()) { "Function already has block body" }

        val body = requireNotNull(function.bodyExpression) { "No body expression found" }

        val filePath = RefactoringUtils.filePath(ktFile)

        // Determine if return type is Unit
        val descriptor = analysis.bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, function]
        val returnType = (descriptor as? org.jetbrains.kotlin.descriptors.FunctionDescriptor)?.returnType
        val isUnit = returnType?.toString() == "Unit"

        val indent = RefactoringUtils.indentationAt(ktFile.text, line)
        val bodyIndent = "$indent    "

        val text = ktFile.text
        var eqOffset = body.textOffset - 1
        while (eqOffset > 0 && text[eqOffset] != '=') eqOffset--

        val bodyText = body.text
        val blockBody = if (isUnit) {
            "{\n$bodyIndent$bodyText\n$indent}"
        } else {
            "{\n${bodyIndent}return $bodyText\n$indent}"
        }

        return listOf(TextEdit(filePath, eqOffset, body.textRange.endOffset - eqOffset, blockBody))
    }
}
