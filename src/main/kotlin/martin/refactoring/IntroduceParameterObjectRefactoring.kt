package martin.refactoring

import martin.compiler.AnalysisResult
import martin.rewriter.TextEdit
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import java.nio.file.Path

/**
 * Introduce parameter object: groups consecutive parameters of a function into a data class.
 * Updates the function declaration, body, and all call sites.
 */
class IntroduceParameterObjectRefactoring(private val analysis: AnalysisResult) {

    fun introduce(
        file: Path,
        line: Int,
        col: Int,
        className: String,
        paramNames: List<String>,
    ): List<TextEdit> {
        val (ktFile, element) = RefactoringUtils.findElementAt(analysis, file, line, col)
        val function = requireNotNull(RefactoringUtils.findParent<KtNamedFunction>(element)) { "No function found at $file:$line:$col" }

        val descriptor = requireNotNull(analysis.bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, function] as? FunctionDescriptor) { "Cannot resolve function" }

        val allParams = function.valueParameters
        val selectedParams = allParams.filter { it.name in paramNames }
        val remainingParams = allParams.filter { it.name !in paramNames }

        require(selectedParams.isNotEmpty()) { "No matching parameters found for: $paramNames" }

        val filePath = RefactoringUtils.filePath(ktFile)
        val edits = mutableListOf<TextEdit>()

        val dataClassParams = selectedParams.joinToString(", ") { param ->
            val typeText = param.typeReference?.text ?: RefactoringUtils.FALLBACK_TYPE
            "val ${param.name}: $typeText"
        }
        val dataClassDecl = "data class $className($dataClassParams)\n\n"

        var insertOffset = function.textOffset
        val text = ktFile.text
        while (insertOffset > 0 && text[insertOffset - 1] != '\n') insertOffset--
        edits.add(TextEdit(filePath, insertOffset, 0, dataClassDecl))

        val parameterObjectName = className.replaceFirstChar { it.lowercase() }
        val newParamList = buildList {
            for (param in allParams) {
                if (param.name in paramNames) {
                    // Only add the object param once (at position of first selected param)
                    if (param == selectedParams.first()) {
                        add("$parameterObjectName: $className")
                    }
                } else {
                    add(param.text)
                }
            }
        }.joinToString(", ")

        val paramListElement = function.valueParameterList!!
        val paramListStart = paramListElement.textOffset + 1
        val paramListEnd = paramListElement.textRange.endOffset - 1
        edits.add(TextEdit(filePath, paramListStart, paramListEnd - paramListStart, newParamList))

        // Update references to old params in function body to use `objParam.name`
        val body = function.bodyExpression
        if (body != null) {
            body.accept(object : KtTreeVisitorVoid() {
                override fun visitReferenceExpression(expression: KtReferenceExpression) {
                    super.visitReferenceExpression(expression)
                    if (expression is KtNameReferenceExpression) {
                        val refName = expression.getReferencedName()
                        if (refName in paramNames) {
                            val target = analysis.bindingContext[BindingContext.REFERENCE_TARGET, expression]
                            val paramDescriptors = descriptor.valueParameters.map { it.original }
                            if (target != null && target.original in paramDescriptors) {
                                edits.add(TextEdit(filePath, expression.textOffset, expression.textLength, "$parameterObjectName.$refName"))
                            }
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
                    val argList = callExpr.valueArgumentList ?: return

                    val newArgs = mutableListOf<String>()
                    val selectedArgTexts = mutableListOf<String>()
                    var firstSelectedIdx = -1

                    for ((i, param) in allParams.withIndex()) {
                        if (param.name in paramNames) {
                            if (firstSelectedIdx == -1) firstSelectedIdx = newArgs.size
                            val argText = if (i < args.size) args[i].getArgumentExpression()?.text ?: "TODO()" else "TODO()"
                            selectedArgTexts.add(argText)
                        } else {
                            newArgs.add(if (i < args.size) args[i].text else "TODO()")
                        }
                    }

                    // Insert constructor call at position of first selected param
                    val constructorCall = "$className(${selectedArgTexts.joinToString(", ")})"
                    newArgs.add(firstSelectedIdx, constructorCall)

                    val newArgListText = newArgs.joinToString(", ")
                    val argListStart = argList.textOffset + 1
                    val argListEnd = argList.textRange.endOffset - 1
                    edits.add(TextEdit(refFilePath, argListStart, argListEnd - argListStart, newArgListText))
                }
            })
        }

        return with(RefactoringUtils) { edits.sortedForApplication() }
    }
}
