package martin.refactoring.extract

import martin.refactoring.*
import martin.compiler.AnalysisResult
import martin.rewriter.TextEdit
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import java.nio.file.Path

/**
 * Extract parameter: takes a hardcoded expression inside a function body and turns it into a parameter.
 * All call sites are updated to pass the original expression as an argument.
 */
class ExtractParameterRefactoring(private val analysis: AnalysisResult) : Refactoring {

    override val name = "extract-parameter"
    override val description = "Extract a hardcoded expression into a function parameter. All call sites are updated to pass the original value"
    override val params = listOf(
        ParamDef("name", ParamType.STRING, "Name for the new parameter"),
    )

    override fun execute(ctx: RefactoringContext): RefactoringOutput {
        return RefactoringOutput.edits(extract(ctx.file, ctx.line, ctx.col, ctx.string("name")))
    }

    fun extract(file: Path, line: Int, col: Int, paramName: String): List<TextEdit> {
        val (ktFile, element) = RefactoringUtils.findElementAt(analysis, file, line, col)

        val expression = requireNotNull(findExtractableExpression(element)) { "No extractable expression found at $file:$line:$col" }

        val exprText = expression.text

        val function = requireNotNull(RefactoringUtils.findParent<KtNamedFunction>(expression)) { "Expression is not inside a function" }

        val descriptor = requireNotNull(analysis.bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, function] as? FunctionDescriptor) { "Cannot resolve function" }

        // Determine the type of the expression
        val type = analysis.bindingContext.getType(expression)
        val typeText = type?.toString() ?: RefactoringUtils.FALLBACK_TYPE

        val filePath = RefactoringUtils.filePath(ktFile)
        val edits = mutableListOf<TextEdit>()

        val paramList = function.valueParameterList
        if (paramList != null) {
            val newParam = "$paramName: $typeText"
            val closeParen = paramList.textRange.endOffset - 1
            val prefix = if (function.valueParameters.isNotEmpty()) ", " else ""
            edits.add(TextEdit(filePath, closeParen, 0, "$prefix$newParam"))
        }

        edits.add(TextEdit(filePath, expression.textOffset, expression.textLength, paramName))

        for (refFile in analysis.files) {
            val refFilePath = RefactoringUtils.filePath(refFile)
            refFile.accept(object : KtTreeVisitorVoid() {
                override fun visitCallExpression(callExpr: KtCallExpression) {
                    super.visitCallExpression(callExpr)
                    val callee = callExpr.calleeExpression as? KtReferenceExpression ?: return
                    val target = analysis.bindingContext[BindingContext.REFERENCE_TARGET, callee]
                    if (target != null && target.original == descriptor.original) {
                        val argList = callExpr.valueArgumentList
                        if (argList != null) {
                            val closeP = argList.textRange.endOffset - 1
                            val argPrefix = if (callExpr.valueArguments.isNotEmpty()) ", " else ""
                            edits.add(TextEdit(refFilePath, closeP, 0, "$argPrefix$exprText"))
                        }
                    }
                }
            })
        }

        return with(RefactoringUtils) { edits.sortedForApplication() }
    }

    private fun findExtractableExpression(element: PsiElement?): KtExpression? {
        var current = element
        while (current != null) {
            if (current is KtExpression && current !is KtBlockExpression && current !is KtNamedFunction
                && current !is KtDeclaration && current !is KtNameReferenceExpression) {
                return current
            }
            current = current.parent
        }
        return null
    }
}
