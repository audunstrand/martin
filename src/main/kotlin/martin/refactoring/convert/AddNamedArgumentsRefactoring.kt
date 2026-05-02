package martin.refactoring.convert

import martin.refactoring.*
import martin.compiler.AnalysisResult
import martin.rewriter.TextEdit
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import java.nio.file.Path

/**
 * Convert positional arguments to named arguments at a call site.
 */
class AddNamedArgumentsRefactoring(private val analysis: AnalysisResult) : Refactoring {

    override val name = "add-named-arguments"
    override val description = "Convert positional arguments to named arguments at a call site"
    override val params = emptyList<ParamDef>()

    override fun execute(ctx: RefactoringContext): RefactoringOutput =
        RefactoringOutput.edits(convert(ctx.file, ctx.line, ctx.col))

    fun convert(file: Path, line: Int, col: Int): List<TextEdit> {
        val (ktFile, element) = RefactoringUtils.findElementAt(analysis, file, line, col)
        val callExpr = requireNotNull(RefactoringUtils.findParent<KtCallExpression>(element)) { "No call expression found at $file:$line:$col" }

        val callee = requireNotNull(callExpr.calleeExpression as? KtReferenceExpression) { "Cannot resolve callee" }

        val descriptor = requireNotNull(analysis.bindingContext[BindingContext.REFERENCE_TARGET, callee] as? FunctionDescriptor) { "Cannot resolve function descriptor" }

        val paramNames = descriptor.valueParameters.map { it.name.asString() }
        val args = callExpr.valueArguments

        if (args.isEmpty()) return emptyList()

        val filePath = RefactoringUtils.filePath(ktFile)
        val edits = mutableListOf<TextEdit>()

        for ((i, arg) in args.withIndex()) {
            // Skip if already named
            if (arg.isNamed()) continue
            if (i >= paramNames.size) break

            val argExpr = arg.getArgumentExpression() ?: continue
            val namedText = "${paramNames[i]} = ${argExpr.text}"
            edits.add(TextEdit(filePath, arg.textOffset, arg.textLength, namedText))
        }

        return with(RefactoringUtils) { edits.sortedForApplication() }
    }
}
