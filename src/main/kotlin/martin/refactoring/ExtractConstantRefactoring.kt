package martin.refactoring

import martin.compiler.AnalysisResult
import martin.rewriter.TextEdit
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import java.nio.file.Path

/**
 * Extract a literal expression into a named constant.
 * Places it as a top-level `const val` or inside a `companion object` if within a class.
 */
class ExtractConstantRefactoring(private val analysis: AnalysisResult) {

    fun extract(file: Path, line: Int, col: Int, constantName: String): List<TextEdit> {
        val (ktFile, element) = RefactoringUtils.findElementAt(analysis, file, line, col)
        val literal = requireNotNull(findLiteral(element)) { "No literal expression found at $file:$line:$col" }

        val literalText = literal.text
        val filePath = RefactoringUtils.filePath(ktFile)
        val edits = mutableListOf<TextEdit>()

        // Determine the type
        val type = analysis.bindingContext.getType(literal)
        val typeText = type?.toString() ?: RefactoringUtils.FALLBACK_TYPE

        // Determine where to place the constant
        val enclosingClass = RefactoringUtils.findParent<KtClassOrObject>(element)

        if (enclosingClass != null) {
            val constDecl = "const val $constantName: $typeText = $literalText"
            RefactoringUtils.insertIntoCompanionObject(enclosingClass, constDecl, filePath, edits)
        } else {
            // Top-level constant - insert at the top of the file (after imports)
            val constDecl = "const val $constantName: $typeText = $literalText\n\n"
            val importList = ktFile.importList
            val insertOffset = if (importList != null && importList.imports.isNotEmpty()) {
                importList.textRange.endOffset + 1
            } else {
                val pkg = ktFile.packageDirective
                if (pkg != null && pkg.text.isNotEmpty()) pkg.textRange.endOffset + 2 else 0
            }
            edits.add(TextEdit(filePath, insertOffset, 0, constDecl))
        }

        edits.add(TextEdit(filePath, literal.textOffset, literal.textLength, constantName))

        return with(RefactoringUtils) { edits.sortedForApplication() }
    }

    private fun findLiteral(element: PsiElement?): KtExpression? {
        var current = element
        while (current != null) {
            when (current) {
                is KtConstantExpression -> return current
                is KtStringTemplateExpression -> {
                    if (current.entries.all { it is KtLiteralStringTemplateEntry }) return current
                }
            }
            if (current is KtExpression && current !is KtConstantExpression && current !is KtStringTemplateExpression) {
                break
            }
            current = current.parent
        }
        return null
    }
}
