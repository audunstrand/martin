package martin.refactoring.convert

import martin.refactoring.*
import martin.compiler.AnalysisResult
import martin.rewriter.TextEdit
import org.jetbrains.kotlin.psi.*
import java.nio.file.Path

/**
 * Convert an abstract class to a sealed class.
 * Removes `abstract`, adds `sealed` modifier.
 */
class ConvertToSealedClassRefactoring(private val analysis: AnalysisResult) : Refactoring {

    override val name = "convert-to-sealed-class"
    override val description = "Convert an abstract class to a sealed class"
    override val params = emptyList<ParamDef>()

    override fun execute(ctx: RefactoringContext): RefactoringOutput =
        RefactoringOutput.edits(convert(ctx.file, ctx.line, ctx.col))

    fun convert(file: Path, line: Int, col: Int): List<TextEdit> {
        val (ktFile, element) = RefactoringUtils.findElementAt(analysis, file, line, col)
        val classDecl = requireNotNull(RefactoringUtils.findParent<KtClass>(element)) { "No class found at $file:$line:$col" }

        require(classDecl.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.ABSTRACT_KEYWORD)) { "Class must be abstract to convert to sealed" }

        require(!classDecl.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.SEALED_KEYWORD)) { "Class is already sealed" }

        val filePath = RefactoringUtils.filePath(ktFile)
        val edits = mutableListOf<TextEdit>()

        val modifierList = classDecl.modifierList
        if (modifierList != null) {
            val abstractKeyword = modifierList.getModifier(org.jetbrains.kotlin.lexer.KtTokens.ABSTRACT_KEYWORD)
            if (abstractKeyword != null) {
                edits.add(TextEdit(filePath, abstractKeyword.textOffset, abstractKeyword.textLength, "sealed"))
            }
        }

        return with(RefactoringUtils) { edits.sortedForApplication() }
    }
}
