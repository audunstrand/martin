package martin.refactoring

import martin.compiler.AnalysisResult
import martin.rewriter.TextEdit
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import java.nio.file.Path

/**
 * Convert a function to an extension function.
 * The first parameter becomes the receiver type.
 * `fun process(list: List<Int>, x: Int)` -> `fun List<Int>.process(x: Int)`
 * Updates all call sites: `process(myList, 5)` -> `myList.process(5)`
 */
class ConvertToExtensionFunctionRefactoring(private val analysis: AnalysisResult) {

    fun convert(file: Path, line: Int, col: Int): List<TextEdit> {
        val (ktFile, element) = RefactoringUtils.findElementAt(analysis, file, line, col)
        val function = requireNotNull(RefactoringUtils.findParent<KtNamedFunction>(element)) { "No function found at $file:$line:$col" }

        val params = function.valueParameters
        require(params.isNotEmpty()) { "Function must have at least one parameter to convert to extension function" }

        val firstParam = params[0]
        val receiverType = requireNotNull(firstParam.typeReference?.text) { "First parameter must have an explicit type" }
        val firstParamName = requireNotNull(firstParam.name) { "First parameter must have a name" }

        val descriptor = requireNotNull(analysis.bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, function] as? FunctionDescriptor) { "Cannot resolve function" }

        val filePath = RefactoringUtils.filePath(ktFile)
        val edits = mutableListOf<TextEdit>()

        val funcName = requireNotNull(function.nameIdentifier) { "Function has no name" }
        edits.add(TextEdit(filePath, funcName.textOffset, 0, "$receiverType."))

        val paramList = function.valueParameterList!!
        if (params.size == 1) {
            // Only one param - clear the param list
            val paramStart = paramList.textOffset + 1
            val paramEnd = paramList.textRange.endOffset - 1
            edits.add(TextEdit(filePath, paramStart, paramEnd - paramStart, ""))
        } else {
            val firstParamEnd = firstParam.textRange.endOffset
            var removeEnd = firstParamEnd
            val text = ktFile.text
            // Skip comma and whitespace after first param
            while (removeEnd < text.length && (text[removeEnd] == ',' || text[removeEnd] == ' ')) removeEnd++
            edits.add(TextEdit(filePath, firstParam.textOffset, removeEnd - firstParam.textOffset, ""))
        }

        val body = function.bodyExpression
        if (body != null) {
            body.accept(object : KtTreeVisitorVoid() {
                override fun visitReferenceExpression(expression: KtReferenceExpression) {
                    super.visitReferenceExpression(expression)
                    if (expression is KtNameReferenceExpression && expression.getReferencedName() == firstParamName) {
                        val target = analysis.bindingContext[BindingContext.REFERENCE_TARGET, expression]
                        if (target != null && target.original == descriptor.valueParameters[0].original) {
                            edits.add(TextEdit(filePath, expression.textOffset, expression.textLength, "this"))
                        }
                    }
                }
            })
        }

        for (refFile in analysis.files) {
            val refFilePath = RefactoringUtils.filePath(refFile)
            refFile.accept(object : KtTreeVisitorVoid() {
                override fun visitCallExpression(callExpr: KtCallExpression) {
                    super.visitCallExpression(callExpr)
                    val callee = callExpr.calleeExpression as? KtReferenceExpression ?: return
                    val target = analysis.bindingContext[BindingContext.REFERENCE_TARGET, callee]
                    if (target == null || target.original != descriptor.original) return

                    val args = callExpr.valueArguments
                    if (args.isEmpty()) return

                    val firstArgText = args[0].getArgumentExpression()?.text ?: return
                    val remainingArgs = args.drop(1).joinToString(", ") { it.text }

                    val argList = callExpr.valueArgumentList ?: return
                    val newCallText = "$firstArgText.${callee.text}($remainingArgs)"

                    edits.add(TextEdit(refFilePath, callExpr.textOffset, callExpr.textLength, newCallText))
                }
            })
        }

        return with(RefactoringUtils) { edits.sortedForApplication() }
    }
}
