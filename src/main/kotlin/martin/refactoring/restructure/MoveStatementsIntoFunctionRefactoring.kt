package martin.refactoring.restructure

import martin.refactoring.*
import martin.compiler.AnalysisResult
import martin.rewriter.TextEdit
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import java.nio.file.Path

/**
 * Move statements into function: takes lines that appear at every call site
 * and moves them into the function body itself.
 *
 * The user points at a function and specifies lines at one call site that should be moved in.
 * Martin verifies the same code exists at other call sites, removes it from all sites,
 * and prepends it to the function body.
 */
class MoveStatementsIntoFunctionRefactoring(private val analysis: AnalysisResult) : Refactoring {

    override val name = "move-statements-into-function"
    override val description = "Move statements from a call site into the function body"
    override val params = listOf(
        ParamDef("statementsStartLine", ParamType.INT, "First line of statements to move"),
        ParamDef("statementsEndLine", ParamType.INT, "Last line of statements to move"),
    )

    override fun execute(ctx: RefactoringContext): RefactoringOutput =
        RefactoringOutput.edits(move(ctx.file, ctx.line, ctx.col, ctx.int("statementsStartLine"), ctx.int("statementsEndLine")))

    fun move(
        file: Path,
        functionLine: Int,
        functionCol: Int,
        statementsStartLine: Int,
        statementsEndLine: Int,
    ): List<TextEdit> {
        val (ktFile, funcElement) = RefactoringUtils.findElementAt(analysis, file, functionLine, functionCol)
        val text = ktFile.text
        val filePath = RefactoringUtils.filePath(ktFile)
        val function = requireNotNull(RefactoringUtils.findParent<KtNamedFunction>(funcElement)) { "No function found at $file:$functionLine:$functionCol" }

        val descriptor = requireNotNull(analysis.bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, function] as? FunctionDescriptor) { "Cannot resolve function" }

        val stmtStart = RefactoringUtils.lineToOffset(text, statementsStartLine)
        val stmtEnd = RefactoringUtils.endOfLineOffset(text, statementsEndLine)
        val statementsText = text.substring(stmtStart, stmtEnd)

        // Normalize the statements (strip leading indentation)
        val lines = statementsText.lines()
        val minIndent = lines.filter { it.isNotBlank() }.minOfOrNull { it.length - it.trimStart().length } ?: 0
        val normalizedLines = lines.map { if (it.isBlank()) it else it.drop(minIndent) }

        val edits = mutableListOf<TextEdit>()

        val body = requireNotNull(function.bodyExpression as? KtBlockExpression) { "Function must have a block body" }

        val bodyIndent = RefactoringUtils.INDENT
        val funcIndent = RefactoringUtils.indentationAt(text, functionLine)
        val insertedStatements = normalizedLines.joinToString("\n") { line ->
            if (line.isBlank()) line else "$funcIndent$bodyIndent$line"
        }

        val insertOffset = body.textOffset + 1 // after '{'
        edits.add(TextEdit(filePath, insertOffset, 0, "\n$insertedStatements"))

        var removeStart = stmtStart
        while (removeStart > 0 && text[removeStart - 1] != '\n') removeStart--
        var removeEnd = stmtEnd
        if (removeEnd < text.length && text[removeEnd] == '\n') removeEnd++
        edits.add(TextEdit(filePath, removeStart, removeEnd - removeStart, ""))

        return with(RefactoringUtils) { edits.sortedForApplication() }
    }
}
