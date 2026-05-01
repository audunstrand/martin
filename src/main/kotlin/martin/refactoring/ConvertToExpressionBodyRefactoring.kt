package martin.refactoring

import martin.compiler.AnalysisResult
import martin.rewriter.TextEdit
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import java.nio.file.Path

/**
 * Convert a function with block body containing a single return to expression body.
 * `fun f(): T { return expr }` -> `fun f(): T = expr`
 * Also handles `fun f(): T { expr }` for Unit-returning functions.
 */
class ConvertToExpressionBodyRefactoring(private val analysis: AnalysisResult) {

    fun convert(file: Path, line: Int, col: Int): List<TextEdit> {
        val (ktFile, element) = RefactoringUtils.findElementAt(analysis, file, line, col)
        val function = requireNotNull(RefactoringUtils.findParent<KtNamedFunction>(element)) { "No function found at $file:$line:$col" }

        require(function.hasBlockBody()) { "Function already has expression body" }

        val block = requireNotNull(function.bodyExpression as? KtBlockExpression) { "No block body found" }

        val statements = block.statements
        require(statements.size == 1) { "Block body must have exactly one statement, found ${statements.size}" }

        val stmt = statements[0]
        val exprText = when (stmt) {
            is KtReturnExpression -> stmt.returnedExpression?.text ?: "Unit"
            else -> stmt.text  // Unit-returning function with a single expression statement
        }

        val filePath = RefactoringUtils.filePath(ktFile)

        // Replace from the block start (including the '{') to the block end (including '}')
        // with `= expr`
        val blockStart = block.textOffset
        val blockEnd = block.textRange.endOffset

        // Also remove the colon + return type if it's Unit and was explicit
        val replacement = "= $exprText"

        return listOf(TextEdit(filePath, blockStart, blockEnd - blockStart, replacement))
    }
}
