package martin.refactoring

import martin.compiler.AnalysisResult
import martin.rewriter.TextEdit
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import java.nio.file.Path

/**
 * Replace constructor with factory function.
 * Makes the primary constructor private and adds a companion object factory function.
 * Updates all constructor call sites to use the factory.
 */
class ReplaceConstructorWithFactoryRefactoring(private val analysis: AnalysisResult) {

    fun replace(file: Path, line: Int, col: Int, factoryName: String = "create"): List<TextEdit> {
        val (ktFile, element) = RefactoringUtils.findElementAt(analysis, file, line, col)
        val classDecl = requireNotNull(RefactoringUtils.findParent<KtClass>(element)) { "No class found at $file:$line:$col" }

        val className = requireNotNull(classDecl.name) { "Class has no name" }

        val filePath = RefactoringUtils.filePath(ktFile)
        val edits = mutableListOf<TextEdit>()

        val primaryConstructor = classDecl.primaryConstructor
        val paramListText = primaryConstructor?.valueParameterList?.text ?: "()"
        val paramNames = primaryConstructor?.valueParameters?.mapNotNull {
            it.name
        } ?: emptyList()
        val argsText = paramNames.joinToString(", ")

        // Make constructor private
        if (primaryConstructor != null) {
            if (!primaryConstructor.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.PRIVATE_KEYWORD)) {
                val constructorKeyword = primaryConstructor.getConstructorKeyword()
                if (constructorKeyword != null) {
                    edits.add(TextEdit(filePath, constructorKeyword.textOffset, 0, "private "))
                } else {
                    // No explicit 'constructor' keyword - need to add it
                    val insertAt = primaryConstructor.textOffset
                    edits.add(TextEdit(filePath, insertAt, 0, " private constructor"))
                }
            }
        }

        val factoryFun = "fun $factoryName$paramListText = $className($argsText)"
        RefactoringUtils.insertIntoCompanionObject(classDecl, factoryFun, filePath, edits)

        val classDescriptor = analysis.bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, classDecl]
        if (classDescriptor != null) {
            for (refFile in analysis.files) {
                val refFilePath = RefactoringUtils.filePath(refFile)
                refFile.accept(object : KtTreeVisitorVoid() {
                    override fun visitCallExpression(expression: KtCallExpression) {
                        super.visitCallExpression(expression)
                        val callee = expression.calleeExpression
                        if (callee is KtNameReferenceExpression && callee.getReferencedName() == className) {
                            val target = analysis.bindingContext[BindingContext.REFERENCE_TARGET, callee]
                            if (target is org.jetbrains.kotlin.descriptors.ConstructorDescriptor) {
                                edits.add(TextEdit(refFilePath, callee.textOffset, callee.textLength, "$className.$factoryName"))
                            }
                        }
                    }
                })
            }
        }

        return with(RefactoringUtils) { edits.sortedForApplication() }
    }
}
