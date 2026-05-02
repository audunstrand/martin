package martin.refactoring

import martin.compiler.AnalysisResult
import martin.rewriter.TextEdit
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.types.KotlinType
import java.nio.file.Path

/**
 * Extract function refactoring: extracts a range of lines into a new function.
 *
 * Analyzes the selected code to determine:
 * - Variables read that are declared outside the selection (become parameters)
 * - Variables written inside the selection and used after (become return value)
 * - The extracted function is inserted right after the enclosing function
 */
class ExtractFunctionRefactoring(private val analysis: AnalysisResult) {

    fun extract(file: Path, startLine: Int, endLine: Int, functionName: String): List<TextEdit> {
        val (ktFile, _) = RefactoringUtils.findElementAt(analysis, file, startLine, 0)
        val text = ktFile.text
        val filePath = RefactoringUtils.filePath(ktFile)

        val startOffset = RefactoringUtils.lineToOffset(text, startLine)
        val endOffset = RefactoringUtils.endOfLineOffset(text, endLine)
        val selectedText = text.substring(startOffset, endOffset)

        val elementAtStart = ktFile.findElementAt(startOffset)
        val enclosingFunction = RefactoringUtils.findParent<KtNamedFunction>(elementAtStart)
        val enclosingInitializer = if (enclosingFunction == null) RefactoringUtils.findParent<KtClassInitializer>(elementAtStart) else null
        val enclosingDeclaration: KtDeclaration? = enclosingFunction ?: enclosingInitializer

        val extractionIndent = RefactoringUtils.indentationAt(text, startLine)

        // Compute the indent for the new function based on where it will be inserted,
        // not where the code was extracted from.
        val insertOffset = if (enclosingDeclaration != null) {
            enclosingDeclaration.textRange.endOffset
        } else {
            text.length
        }
        val targetIndent = if (enclosingDeclaration != null) {
            val funcLine = RefactoringUtils.offsetToLineCol(text, enclosingDeclaration.textOffset).first
            RefactoringUtils.indentationAt(text, funcLine)
        } else {
            ""
        }

        val (declaredBefore, declaredInSelection, usedInSelection, usedAfterSelection) =
            categorizeVariables(enclosingDeclaration, startOffset, endOffset)

        val params = declaredBefore.filter { it.key in usedInSelection }
        val paramList = params.entries.joinToString(", ") { (name, type) ->
            "$name: ${type?.toString() ?: RefactoringUtils.FALLBACK_TYPE}"
        }

        val returnVars = declaredInSelection.intersect(usedAfterSelection)

        val reindentedBody = reindentBody(selectedText, extractionIndent, targetIndent)

        val (returnType, finalBody) = determineReturnType(returnVars, declaredBefore, params, reindentedBody, targetIndent)

        val needsSuspend = containsSuspendCalls(ktFile, startOffset, endOffset)
        val visibility = if (enclosingDeclaration != null) "private " else ""
        val suspendModifier = if (needsSuspend) "suspend " else ""
        val newFunction = "\n${targetIndent}${visibility}${suspendModifier}fun $functionName($paramList)$returnType {\n$finalBody\n${targetIndent}}\n"

        val callExpr = buildCallExpression(functionName, params.keys, returnVars, extractionIndent)

        val edits = mutableListOf<TextEdit>()

        edits.add(TextEdit(filePath, startOffset, endOffset - startOffset, callExpr))
        edits.add(TextEdit(filePath, insertOffset, 0, newFunction))

        return with(RefactoringUtils) { edits.sortedForApplication() }
    }

    private data class VariableCategories(
        val declaredBefore: Map<String, KotlinType?>,
        val declaredInSelection: Set<String>,
        val usedInSelection: Set<String>,
        val usedAfterSelection: Set<String>,
    )

    private fun categorizeVariables(
        enclosingDeclaration: KtDeclaration?,
        startOffset: Int,
        endOffset: Int,
    ): VariableCategories {
        val declaredBefore = mutableMapOf<String, KotlinType?>()
        val declaredInSelection = mutableSetOf<String>()
        val usedInSelection = mutableSetOf<String>()
        val usedAfterSelection = mutableSetOf<String>()

        if (enclosingDeclaration != null) {
            enclosingDeclaration.accept(object : KtTreeVisitorVoid() {
                override fun visitProperty(property: KtProperty) {
                    super.visitProperty(property)
                    val name = property.name ?: return
                    val propOffset = property.textOffset
                    if (propOffset < startOffset) {
                        val descriptor = analysis.bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, property]
                        declaredBefore[name] = (descriptor as? org.jetbrains.kotlin.descriptors.VariableDescriptor)?.type
                    } else if (propOffset in startOffset until endOffset) {
                        declaredInSelection.add(name)
                    }
                }

                override fun visitParameter(parameter: KtParameter) {
                    super.visitParameter(parameter)
                    val name = parameter.name ?: return
                    val paramOffset = parameter.textOffset
                    if (paramOffset < startOffset) {
                        val descriptor = analysis.bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, parameter]
                        declaredBefore[name] = (descriptor as? ValueParameterDescriptor)?.type
                    } else if (paramOffset in startOffset until endOffset) {
                        declaredInSelection.add(name)
                    }
                }

                override fun visitReferenceExpression(expression: KtReferenceExpression) {
                    super.visitReferenceExpression(expression)
                    if (expression !is KtNameReferenceExpression) return
                    val name = expression.getReferencedName()
                    val refOffset = expression.textOffset
                    if (refOffset in startOffset until endOffset) {
                        usedInSelection.add(name)
                    } else if (refOffset >= endOffset) {
                        usedAfterSelection.add(name)
                    }
                }
            })
        }

        return VariableCategories(declaredBefore, declaredInSelection, usedInSelection, usedAfterSelection)
    }

    private fun reindentBody(selectedText: String, extractionIndent: String, targetIndent: String): String {
        val bodyIndent = targetIndent + RefactoringUtils.INDENT
        return selectedText.lines().joinToString("\n") { line ->
            if (line.isBlank()) line
            else {
                val trimmed = line.removePrefix(extractionIndent)
                "$bodyIndent$trimmed"
            }
        }
    }

    private fun determineReturnType(
        returnVars: Set<String>,
        declaredBefore: Map<String, KotlinType?>,
        params: Map<String, KotlinType?>,
        reindentedBody: String,
        targetIndent: String,
    ): Pair<String, String> {
        val bodyIndent = targetIndent + RefactoringUtils.INDENT
        return when {
            returnVars.isEmpty() -> Pair("", reindentedBody)
            returnVars.size == 1 -> {
                val varName = returnVars.first()
                val varType = declaredBefore[varName]?.toString()
                    ?: params[varName]?.toString()
                    ?: RefactoringUtils.FALLBACK_TYPE
                Pair(": $varType", "$reindentedBody\n${bodyIndent}return $varName")
            }
            else -> {
                val returnExpr = returnVars.joinToString(", ")
                Pair("", "$reindentedBody\n${bodyIndent}return Triple($returnExpr) // TODO: adjust return type")
            }
        }
    }

    private fun buildCallExpression(
        functionName: String,
        paramNames: Set<String>,
        returnVars: Set<String>,
        baseIndent: String,
    ): String {
        val args = paramNames.joinToString(", ")
        return when {
            returnVars.isEmpty() -> "${baseIndent}$functionName($args)"
            returnVars.size == 1 -> "${baseIndent}val ${returnVars.first()} = $functionName($args)"
            else -> {
                val destructure = returnVars.joinToString(", ")
                "${baseIndent}val ($destructure) = $functionName($args)"
            }
        }
    }

    private fun containsSuspendCalls(ktFile: KtFile, startOffset: Int, endOffset: Int): Boolean {
        var found = false
        ktFile.accept(object : KtTreeVisitorVoid() {
            override fun visitCallExpression(expression: KtCallExpression) {
                super.visitCallExpression(expression)
                if (found) return
                val callOffset = expression.textOffset
                if (callOffset !in startOffset until endOffset) return
                val callee = expression.calleeExpression as? KtReferenceExpression ?: return
                val resolved = analysis.bindingContext[BindingContext.REFERENCE_TARGET, callee]
                if (resolved is FunctionDescriptor && resolved.isSuspend) {
                    found = true
                }
            }
        })
        return found
    }
}
