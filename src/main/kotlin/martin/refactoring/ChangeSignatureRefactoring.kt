package martin.refactoring

import martin.compiler.AnalysisResult
import martin.rewriter.TextEdit
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import java.nio.file.Path

/**
 * Change signature refactoring: modify a function's parameters.
 *
 * Supports:
 * - Adding new parameters (with default values)
 * - Removing parameters
 * - Reordering parameters
 * - Renaming parameters
 *
 * Updates the function declaration and all call sites.
 */
class ChangeSignatureRefactoring(private val analysis: AnalysisResult) {

    data class ParameterSpec(
        val name: String,
        val type: String,
        val defaultValue: String? = null,
    )

    /**
     * Change the signature of a function at the given location.
     *
     * @param newParams The new parameter list. Parameters matching existing ones by name
     *        are kept (possibly reordered/renamed). New names are added. Missing old names are removed.
     */
    fun changeSignature(
        file: Path,
        line: Int,
        col: Int,
        newParams: List<ParameterSpec>,
    ): List<TextEdit> {
        val (ktFile, elementAtCursor) = RefactoringUtils.findElementAt(analysis, file, line, col)
        requireNotNull(elementAtCursor) { "No element found at $file:$line:$col" }

        val function = requireNotNull(RefactoringUtils.findParent<KtNamedFunction>(elementAtCursor)) { "No function found at $file:$line:$col" }

        val descriptor = requireNotNull(analysis.bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, function] as? FunctionDescriptor) { "Could not resolve function at $file:$line:$col" }

        val oldParams = function.valueParameters
        val oldParamNames = oldParams.map { it.name ?: "" }

        val edits = mutableListOf<TextEdit>()

        val newParamListText = newParams.joinToString(", ") { param ->
            val defaultPart = if (param.defaultValue != null) " = ${param.defaultValue}" else ""
            "${param.name}: ${param.type}$defaultPart"
        }

        val paramListElement = function.valueParameterList
        if (paramListElement != null) {
            val filePath = RefactoringUtils.filePath(ktFile)
            val openParen = paramListElement.textOffset + 1 // skip '('
            val closeParen = paramListElement.textRange.endOffset - 1 // before ')'
            edits.add(TextEdit(filePath, openParen, closeParen - openParen, newParamListText))
        }

        // Build mapping from old param index to new param position
        // For call site updates, we need to know how to rearrange arguments
        val oldToNewMapping = mutableMapOf<Int, Int>() // oldIndex -> newIndex
        for ((newIdx, newParam) in newParams.withIndex()) {
            val oldIdx = oldParamNames.indexOf(newParam.name)
            if (oldIdx >= 0) {
                oldToNewMapping[oldIdx] = newIdx
            }
        }

        val callSites = RefactoringUtils.findAllCallSites(analysis, descriptor)

        for (callExpr in callSites) {
            val callFile = callExpr.containingFile as KtFile
            val callFilePath = RefactoringUtils.filePath(callFile)

            val argList = callExpr.valueArgumentList ?: continue
            val oldArgs = callExpr.valueArguments

            val newArgTexts = Array(newParams.size) { "" }
            for ((oldIdx, arg) in oldArgs.withIndex()) {
                val newIdx = oldToNewMapping[oldIdx]
                if (newIdx != null) {
                    newArgTexts[newIdx] = arg.getArgumentExpression()?.text ?: ""
                }
                // If old param is not in new list, it's removed (arg dropped)
            }

            // Fill in defaults for new parameters that don't have a corresponding old arg
            for ((newIdx, param) in newParams.withIndex()) {
                if (newArgTexts[newIdx].isEmpty()) {
                    // New parameter - use its default value or a placeholder
                    newArgTexts[newIdx] = param.defaultValue ?: "TODO(\"provide ${param.name}\")"
                }
            }

            val newArgListText = newArgTexts.joinToString(", ")
            val openParen = argList.textOffset + 1
            val closeParen = argList.textRange.endOffset - 1
            edits.add(TextEdit(callFilePath, openParen, closeParen - openParen, newArgListText))
        }

        return with(RefactoringUtils) { edits.sortedForApplication() }
    }

}
