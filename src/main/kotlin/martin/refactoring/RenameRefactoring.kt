package martin.refactoring

import martin.compiler.AnalysisResult
import martin.rewriter.TextEdit
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import java.nio.file.Path

/**
 * Rename refactoring: finds a symbol at a given location and renames all references to it.
 */
class RenameRefactoring(private val analysis: AnalysisResult) {

    fun rename(file: Path, line: Int, col: Int, newName: String): List<TextEdit> {
        val (targetFile, rawElement) = RefactoringUtils.findElementAt(analysis, file, line, col)
        val elementAtCursor = requireNotNull(rawElement) { "No element found at $file:$line:$col" }

        val namedElement = requireNotNull(findNamedElement(elementAtCursor)) { "No renameable symbol found at $file:$line:$col (found: ${elementAtCursor.text})" }

        val descriptor = requireNotNull(resolveToDescriptor(namedElement)) { "Could not resolve symbol at $file:$line:$col" }

        val edits = mutableListOf<TextEdit>()

        val references = RefactoringUtils.findAllReferencesWithDeclaration(analysis, descriptor)
        for (ref in references) {
            val refFile = ref.containingFile as KtFile
            edits.add(TextEdit(
                filePath = RefactoringUtils.filePath(refFile),
                offset = ref.textOffset,
                length = ref.textLength,
                replacement = newName,
            ))
        }

        // Find and update import directives by FQN matching
        // The binding context often doesn't resolve imports in files parsed via KtPsiFactory
        val oldFqn = descriptor.fqNameSafe.asString()
        val oldName = descriptor.name.asString()
        val newFqn = oldFqn.removeSuffix(oldName) + newName

        for (ktFile in analysis.files) {
            val filePath = RefactoringUtils.filePath(ktFile)
            for (importDirective in ktFile.importDirectives) {
                val importedFqn = importDirective.importedFqName?.asString() ?: continue
                if (importedFqn == oldFqn) {
                    val importRef = importDirective.importedReference ?: continue
                    edits.add(TextEdit(
                        filePath = filePath,
                        offset = importRef.textOffset,
                        length = importRef.textLength,
                        replacement = newFqn,
                    ))
                }
            }
        }

        // Deduplicate (an import reference might also show up in the tree visitor)
        val deduplicated = edits
            .distinctBy { "${it.filePath}:${it.offset}:${it.length}" }
            .let { with(RefactoringUtils) { it.sortedForApplication() } }

        return deduplicated
    }

    private fun findNamedElement(element: PsiElement): KtElement? {
        var current: PsiElement? = element
        while (current != null) {
            when (current) {
                is KtNamedDeclaration -> return current
                is KtNameReferenceExpression -> return current
            }
            current = current.parent
        }
        return null
    }

    private fun resolveToDescriptor(element: KtElement): DeclarationDescriptor? {
        val bindingContext = analysis.bindingContext
        return when (element) {
            is KtNamedDeclaration -> bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, element]
            is KtReferenceExpression -> bindingContext[BindingContext.REFERENCE_TARGET, element]
            else -> null
        }
    }

}
