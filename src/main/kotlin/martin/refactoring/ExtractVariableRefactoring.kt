package martin.refactoring

import martin.compiler.AnalysisResult
import martin.rewriter.TextEdit
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import java.nio.file.Path

/**
 * Extract variable refactoring: extracts an expression at a given location into a named val.
 *
 * Finds the expression at the cursor, replaces it with the variable name,
 * and inserts a val declaration before the statement containing the expression.
 */
class ExtractVariableRefactoring(private val analysis: AnalysisResult) {

    fun extract(file: Path, line: Int, col: Int, variableName: String): List<TextEdit> {
        val (ktFile, rawElement) = RefactoringUtils.findElementAt(analysis, file, line, col)
        val text = ktFile.text
        val filePath = RefactoringUtils.filePath(ktFile)

        val elementAtCursor = requireNotNull(rawElement) { "No element found at $file:$line:$col" }

        val expression = requireNotNull(findExtractableExpression(elementAtCursor)) { "No extractable expression found at $file:$line:$col" }

        // Determine the type if available
        val type = analysis.bindingContext.getType(expression)
        val typeAnnotation = if (type != null) ": $type" else ""

        val expressionText = expression.text

        val containingStatement = requireNotNull(findContainingStatement(expression)) { "Could not find containing statement" }

        val statementOffset = containingStatement.textOffset
        val statementLine = RefactoringUtils.offsetToLineCol(text, statementOffset).first
        val lineStartOffset = RefactoringUtils.lineToOffset(text, statementLine)
        val indent = RefactoringUtils.indentationAt(text, statementLine)

        val edits = mutableListOf<TextEdit>()

        val declaration = "${indent}val $variableName$typeAnnotation = $expressionText\n"
        edits.add(TextEdit(filePath, lineStartOffset, 0, declaration))

        edits.add(TextEdit(filePath, expression.textOffset, expression.textLength, variableName))

        return with(RefactoringUtils) { edits.sortedForApplication() }
    }

    private fun findExtractableExpression(element: PsiElement): KtExpression? {
        var current: PsiElement? = element
        while (current != null) {
            if (current is KtExpression && isExtractable(current)) {
                return current
            }
            current = current.parent
        }
        return null
    }

    private fun isExtractable(expr: KtExpression): Boolean {
        // Don't extract entire statements, blocks, or simple name references
        return when (expr) {
            is KtBlockExpression -> false
            is KtNameReferenceExpression -> false  // already a variable
            is KtDeclaration -> false
            is KtStatementExpression -> false
            is KtCallExpression -> true
            is KtDotQualifiedExpression -> true
            is KtBinaryExpression -> true
            is KtStringTemplateExpression -> true
            is KtConstantExpression -> true
            is KtParenthesizedExpression -> true
            is KtArrayAccessExpression -> true
            is KtLambdaExpression -> true
            is KtIfExpression -> true
            is KtWhenExpression -> true
            else -> false
        }
    }

    private fun findContainingStatement(element: PsiElement): PsiElement? {
        var current: PsiElement? = element
        while (current != null) {
            val parent = current.parent
            if (parent is KtBlockExpression || parent is KtFile) {
                return current
            }
            current = parent
        }
        return null
    }
}
