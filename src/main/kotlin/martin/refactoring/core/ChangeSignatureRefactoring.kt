package martin.refactoring.core

import martin.compiler.AnalysisResult
import martin.refactoring.*
import martin.rewriter.TextEdit
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import java.nio.file.Path

/**
 * Change signature refactoring: modify a function's parameters.
 *
 * Supports adding, removing, reordering, and renaming parameters.
 * Updates the function declaration and all call sites.
 */
class ChangeSignatureRefactoring(private val analysis: AnalysisResult) : Refactoring {

    override val name = "change-signature"
    override val description = "Change a function's parameter list. Supports adding, removing, reordering, and renaming parameters. Updates all call sites"
    override val params = listOf(
        ParamDef("params", ParamType.STRING, "New parameter list as comma-separated 'name:Type' or 'name:Type=default' entries"),
    )

    override fun execute(ctx: RefactoringContext): RefactoringOutput {
        val params = parseParameterSpecs(ctx.string("params"))
        return RefactoringOutput.edits(changeSignature(ctx.file, ctx.line, ctx.col, params))
    }

    data class ParameterSpec(
        val name: String,
        val type: String,
        val defaultValue: String? = null,
    )

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
            val openParen = paramListElement.textOffset + 1
            val closeParen = paramListElement.textRange.endOffset - 1
            edits.add(TextEdit(filePath, openParen, closeParen - openParen, newParamListText))
        }

        val oldToNewMapping = mutableMapOf<Int, Int>()
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

            val oldArgByName = mutableMapOf<String, String>()
            for ((i, arg) in oldArgs.withIndex()) {
                val argExpr = arg.getArgumentExpression()?.text ?: continue
                val argName = arg.getArgumentName()?.asName?.asString()
                if (argName != null) {
                    oldArgByName[argName] = argExpr
                } else if (i < oldParamNames.size) {
                    oldArgByName[oldParamNames[i]] = argExpr
                }
            }

            val trailingLambda = callExpr.lambdaArguments.firstOrNull()
            val lastOldParam = oldParamNames.lastOrNull()
            if (trailingLambda != null && lastOldParam != null && lastOldParam !in oldArgByName) {
                oldArgByName[lastOldParam] = trailingLambda.getLambdaExpression()?.text ?: trailingLambda.text
            }

            val newArgTexts = mutableListOf<String>()
            for (param in newParams) {
                val existingArg = oldArgByName[param.name]
                if (existingArg != null) {
                    newArgTexts.add(existingArg)
                } else if (param.defaultValue != null) {
                    continue
                } else {
                    newArgTexts.add("TODO(\"provide ${param.name}\")")
                }
            }

            val newArgListText = newArgTexts.joinToString(", ")

            if (trailingLambda != null) {
                val openParen = argList.textOffset + 1
                val endOfTrailingLambda = trailingLambda.textRange.endOffset
                edits.add(TextEdit(callFilePath, openParen, endOfTrailingLambda - openParen, "$newArgListText)"))
            } else {
                val openParen = argList.textOffset + 1
                val closeParen = argList.textRange.endOffset - 1
                edits.add(TextEdit(callFilePath, openParen, closeParen - openParen, newArgListText))
            }
        }

        return with(RefactoringUtils) { edits.sortedForApplication() }
    }
}

/** Parse a parameter spec string like "name:Type, other:Int=42" into ParameterSpec list. */
fun parseParameterSpecs(input: String): List<ChangeSignatureRefactoring.ParameterSpec> {
    return input.split(",").map { part ->
        val trimmed = part.trim()
        val (nameType, default) = if ("=" in trimmed) {
            val eqIdx = trimmed.indexOf("=")
            trimmed.substring(0, eqIdx).trim() to trimmed.substring(eqIdx + 1).trim()
        } else {
            trimmed to null
        }
        val colonIdx = nameType.indexOf(":")
        require(colonIdx > 0) { "Invalid parameter spec: '$trimmed'. Expected 'name:Type'" }
        ChangeSignatureRefactoring.ParameterSpec(
            name = nameType.substring(0, colonIdx).trim(),
            type = nameType.substring(colonIdx + 1).trim(),
            defaultValue = default,
        )
    }
}
