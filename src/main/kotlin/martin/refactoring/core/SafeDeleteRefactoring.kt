package martin.refactoring.core

import martin.compiler.AnalysisResult
import martin.refactoring.*
import martin.rewriter.TextEdit
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import java.nio.file.Path

/**
 * Safe delete: removes a declaration only if it has no usages.
 * Fails with an error listing usages if any are found.
 */
class SafeDeleteRefactoring(private val analysis: AnalysisResult) : Refactoring {

    override val name = "safe-delete"
    override val description = "Delete a declaration only if it has no usages. Fails with a usage list otherwise"
    override val params = emptyList<ParamDef>()

    override fun execute(ctx: RefactoringContext): RefactoringOutput =
        RefactoringOutput.edits(delete(ctx.file, ctx.line, ctx.col))

    data class UsageInfo(val file: Path, val line: Int, val text: String)

    fun delete(file: Path, line: Int, col: Int): List<TextEdit> {
        val (ktFile, element) = RefactoringUtils.findElementAt(analysis, file, line, col)
        val declaration = requireNotNull(RefactoringUtils.findParent<KtNamedDeclaration>(element)) { "No declaration found at $file:$line:$col" }

        val descriptor = requireNotNull(analysis.bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, declaration]) { "Cannot resolve declaration" }

        val usages = findAllUsages(descriptor)
        require(usages.isEmpty()) {
            val usageList = usages.joinToString("\n") { "  ${it.file}:${it.line} - ${it.text}" }
            "Cannot safely delete '${declaration.name}': found ${usages.size} usage(s):\n$usageList"
        }

        val edits = mutableListOf(RefactoringUtils.removeElementLines(declaration))

        val fqn = descriptor.fqNameSafe.asString()
        for (otherFile in analysis.files) {
            for (importDirective in otherFile.importDirectives) {
                val importedFqn = importDirective.importedFqName?.asString() ?: continue
                if (importedFqn == fqn) {
                    edits.add(RefactoringUtils.removeElementLines(importDirective))
                }
            }
        }

        return with(RefactoringUtils) { edits.sortedForApplication() }
    }

    private fun findAllUsages(descriptor: DeclarationDescriptor): List<UsageInfo> {
        val usages = mutableListOf<UsageInfo>()
        for (ktFile in analysis.files) {
            val filePath = RefactoringUtils.filePath(ktFile)
            ktFile.accept(object : KtTreeVisitorVoid() {
                override fun visitReferenceExpression(expression: KtReferenceExpression) {
                    super.visitReferenceExpression(expression)
                    if (expression.parent is KtImportDirective) return
                    if (RefactoringUtils.findParent<KtImportDirective>(expression) != null) return

                    val target = analysis.bindingContext[BindingContext.REFERENCE_TARGET, expression]
                    if (target != null && RefactoringUtils.matchesDescriptor(target, descriptor)) {
                        val (line, _) = RefactoringUtils.offsetToLineCol(ktFile.text, expression.textOffset)
                        usages.add(UsageInfo(filePath, line, expression.text))
                    }
                }

                override fun visitTypeReference(typeReference: KtTypeReference) {
                    super.visitTypeReference(typeReference)
                    val type = analysis.bindingContext[BindingContext.TYPE, typeReference]
                    if (type != null) {
                        val typeDescriptor = type.constructor.declarationDescriptor
                        if (typeDescriptor != null && typeDescriptor.original == descriptor.original) {
                            if (RefactoringUtils.findParent<KtImportDirective>(typeReference) != null) return
                            val (line, _) = RefactoringUtils.offsetToLineCol(ktFile.text, typeReference.textOffset)
                            val existing = usages.any { it.file == filePath && it.line == line }
                            if (!existing) {
                                usages.add(UsageInfo(filePath, line, typeReference.text))
                            }
                        }
                    }
                }
            })
        }
        return usages
    }
}
