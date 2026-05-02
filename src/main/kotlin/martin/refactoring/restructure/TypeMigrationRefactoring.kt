package martin.refactoring.restructure

import martin.refactoring.*
import martin.compiler.AnalysisResult
import martin.rewriter.TextEdit
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.VariableDescriptor
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import java.nio.file.Path

/**
 * Type migration: changes a variable/function return type and propagates the change
 * through data flow (return types, parameters, local variables that receive the value).
 */
class TypeMigrationRefactoring(private val analysis: AnalysisResult) : Refactoring {

    override val name = "type-migration"
    override val description = "Change a variable or function's type annotation and propagate the type change"
    override val params = listOf(ParamDef("newType", ParamType.STRING, "The new type to migrate to"))

    override fun execute(ctx: RefactoringContext): RefactoringOutput =
        RefactoringOutput.edits(migrate(ctx.file, ctx.line, ctx.col, ctx.string("newType")))

    fun migrate(file: Path, line: Int, col: Int, newType: String): List<TextEdit> {
        val (ktFile, element) = RefactoringUtils.findElementAt(analysis, file, line, col)

        val edits = mutableListOf<TextEdit>()

        // Try to find a property or function at cursor
        val property = RefactoringUtils.findParent<KtProperty>(element)
        val function = RefactoringUtils.findParent<KtNamedFunction>(element)

        if (property != null) {
            migrateProperty(property, newType, edits)
        } else if (function != null) {
            migrateFunction(function, newType, edits)
        } else {
            error("No property or function found at $file:$line:$col")
        }

        return with(RefactoringUtils) { edits.sortedForApplication() }
    }

    private fun migrateProperty(property: KtProperty, newType: String, edits: MutableList<TextEdit>) {
        val filePath = RefactoringUtils.filePath(property.containingFile as KtFile)

        // Change the type annotation
        val typeRef = property.typeReference
        if (typeRef != null) {
            edits.add(TextEdit(filePath, typeRef.textOffset, typeRef.textLength, newType))
        } else {
            val nameEnd = property.nameIdentifier?.textRange?.endOffset
                ?: property.textOffset + (property.name?.length ?: 0)
            edits.add(TextEdit(filePath, nameEnd, 0, ": $newType"))
        }

        val descriptor = analysis.bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, property] ?: return
        propagateType(descriptor, newType, edits)
    }

    private fun migrateFunction(function: KtNamedFunction, newType: String, edits: MutableList<TextEdit>) {
        val filePath = RefactoringUtils.filePath(function.containingFile as KtFile)

        // Change return type
        val typeRef = function.typeReference
        if (typeRef != null) {
            edits.add(TextEdit(filePath, typeRef.textOffset, typeRef.textLength, newType))
        } else {
            val body = function.bodyExpression
            if (body != null) {
                val paramListEnd = function.valueParameterList?.textRange?.endOffset
                    ?: function.nameIdentifier?.textRange?.endOffset
                    ?: return
                edits.add(TextEdit(filePath, paramListEnd, 0, ": $newType"))
            }
        }

        // Propagate to variables that receive the return value
        val descriptor = analysis.bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, function] ?: return
        propagateType(descriptor, newType, edits)
    }

    private fun propagateType(descriptor: DeclarationDescriptor, newType: String, edits: MutableList<TextEdit>) {
        // Find variables that are assigned from this symbol and update their types too
        for (ktFile in analysis.files) {
            val filePath = RefactoringUtils.filePath(ktFile)
            ktFile.accept(object : KtTreeVisitorVoid() {
                override fun visitProperty(property: KtProperty) {
                    super.visitProperty(property)
                    val initializer = property.initializer ?: return

                    // Check if the initializer references our descriptor
                    if (initializerReferencesDescriptor(initializer, descriptor)) {
                        val typeRef = property.typeReference
                        if (typeRef != null) {
                            edits.add(TextEdit(filePath, typeRef.textOffset, typeRef.textLength, newType))
                        }
                    }
                }

                override fun visitParameter(parameter: KtParameter) {
                    super.visitParameter(parameter)
                    // Check if this parameter's default value references our descriptor
                    val defaultValue = parameter.defaultValue ?: return
                    if (initializerReferencesDescriptor(defaultValue, descriptor)) {
                        val typeRef = parameter.typeReference
                        if (typeRef != null) {
                            edits.add(TextEdit(filePath, typeRef.textOffset, typeRef.textLength, newType))
                        }
                    }
                }
            })
        }
    }

    private fun initializerReferencesDescriptor(expr: KtExpression, descriptor: DeclarationDescriptor): Boolean {
        if (expr is KtNameReferenceExpression) {
            val target = analysis.bindingContext[BindingContext.REFERENCE_TARGET, expr]
            if (target != null && target.original == descriptor.original) return true
        }
        if (expr is KtCallExpression) {
            val callee = expr.calleeExpression as? KtReferenceExpression
            if (callee != null) {
                val target = analysis.bindingContext[BindingContext.REFERENCE_TARGET, callee]
                if (target != null && target.original == descriptor.original) return true
            }
        }
        return false
    }
}
