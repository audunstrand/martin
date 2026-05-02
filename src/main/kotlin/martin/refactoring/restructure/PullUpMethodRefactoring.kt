package martin.refactoring.restructure

import martin.refactoring.*
import martin.compiler.AnalysisResult
import martin.rewriter.TextEdit
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import java.nio.file.Path

/**
 * Pull up method: moves a method from a class to its superclass.
 * Push down method: moves a method from a superclass to a subclass.
 */
class PullUpMethodRefactoring(private val analysis: AnalysisResult) : Refactoring {

    override val name = "pull-up-method"
    override val description = "Move a method from a subclass to its superclass"
    override val params = emptyList<ParamDef>()

    override fun execute(ctx: RefactoringContext): RefactoringOutput =
        RefactoringOutput.edits(pullUp(ctx.file, ctx.line, ctx.col))

    fun pullUp(file: Path, line: Int, col: Int): List<TextEdit> {
        val (ktFile, element) = RefactoringUtils.findElementAt(analysis, file, line, col)
        val function = requireNotNull(RefactoringUtils.findParent<KtNamedFunction>(element)) { "No function found at $file:$line:$col" }

        val classDecl = requireNotNull(RefactoringUtils.findParent<KtClass>(function)) { "Function is not inside a class" }

        val classDescriptor = requireNotNull(analysis.bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, classDecl] as? ClassDescriptor) { "Cannot resolve class" }

        val superTypes = classDescriptor.typeConstructor.supertypes
        val superClassDescriptor = requireNotNull(superTypes
            .mapNotNull { it.constructor.declarationDescriptor as? ClassDescriptor }
            .firstOrNull { it.kind != org.jetbrains.kotlin.descriptors.ClassKind.INTERFACE }) { "No superclass found for ${classDecl.name}" }

        val superFqName = superClassDescriptor.fqNameSafe.asString()
        val superClassPsi = requireNotNull(analysis.files.flatMap { it.declarations }
            .filterIsInstance<KtClass>()
            .find {
                val desc = analysis.bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, it] as? ClassDescriptor
                desc?.fqNameSafe?.asString() == superFqName
            }) { "Superclass source not found in analyzed files" }

        val superFile = superClassPsi.containingFile as KtFile
        val superFilePath = RefactoringUtils.filePath(superFile)

        val edits = mutableListOf<TextEdit>()

        // Copy the method to the superclass body
        val methodText = function.text
        val superBody = superClassPsi.body
        if (superBody != null) {
            val indent = RefactoringUtils.INDENT
            val insertOffset = superBody.textRange.endOffset - 1 // before closing '}'
            val openModifier = if (!function.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.OPEN_KEYWORD)) "open " else ""
            edits.add(TextEdit(superFilePath, insertOffset, 0, "\n$indent$openModifier$methodText\n"))
        }

        edits.add(RefactoringUtils.removeElementLines(function))

        return with(RefactoringUtils) { edits.sortedForApplication() }
    }

    fun pushDown(file: Path, line: Int, col: Int, targetClassName: String): List<TextEdit> {
        val (ktFile, element) = RefactoringUtils.findElementAt(analysis, file, line, col)
        val function = requireNotNull(RefactoringUtils.findParent<KtNamedFunction>(element)) { "No function found at $file:$line:$col" }

        val targetClass = requireNotNull(analysis.files.flatMap { it.declarations }
            .filterIsInstance<KtClass>()
            .find { it.name == targetClassName }) { "Target class not found: $targetClassName" }

        val targetFile = targetClass.containingFile as KtFile
        val targetFilePath = RefactoringUtils.filePath(targetFile)
        val filePath = RefactoringUtils.filePath(ktFile)

        val edits = mutableListOf<TextEdit>()

        val targetBody = targetClass.body
        if (targetBody != null) {
            val indent = RefactoringUtils.INDENT
            val insertOffset = targetBody.textRange.endOffset - 1
            edits.add(TextEdit(targetFilePath, insertOffset, 0, "\n${indent}override ${function.text}\n"))
        }

        edits.add(RefactoringUtils.removeElementLines(function))

        return with(RefactoringUtils) { edits.sortedForApplication() }
    }
}
