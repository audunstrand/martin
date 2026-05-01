package martin.refactoring

import martin.compiler.AnalysisResult
import martin.rewriter.TextEdit
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import java.nio.file.Path

/**
 * Convert a val property with a custom getter into a function.
 * `val x: T get() = expr` -> `fun x(): T = expr`
 * Updates all usage sites (property access -> function call).
 */
class ConvertPropertyToFunctionRefactoring(private val analysis: AnalysisResult) {

    fun convert(file: Path, line: Int, col: Int): List<TextEdit> {
        val (ktFile, element) = RefactoringUtils.findElementAt(analysis, file, line, col)
        val property = requireNotNull(RefactoringUtils.findParent<KtProperty>(element)) { "No property found at $file:$line:$col" }

        require(!property.isVar) { "Cannot convert var to function" }

        val descriptor = requireNotNull(analysis.bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, property]) { "Cannot resolve property" }

        val filePath = RefactoringUtils.filePath(ktFile)
        val edits = mutableListOf<TextEdit>()

        val name = requireNotNull(property.name) { "Property has no name" }
        val typeRef = property.typeReference?.text
        val getter = property.getter
        val initializer = property.initializer

        val funText = when {
            getter != null && getter.hasBody() -> {
                val body = if (getter.hasBlockBody()) {
                    getter.bodyBlockExpression?.text ?: "{}"
                } else {
                    "= ${getter.bodyExpression?.text ?: "TODO()"}"
                }
                val typeAnnotation = if (typeRef != null) ": $typeRef " else ""
                "fun $name()$typeAnnotation$body"
            }
            initializer != null -> {
                val typeAnnotation = if (typeRef != null) ": $typeRef " else ""
                "fun $name()$typeAnnotation= ${initializer.text}"
            }
            else -> error("Property has no getter or initializer")
        }

        var declStart = property.textOffset
        val text = ktFile.text
        while (declStart > 0 && text[declStart - 1] != '\n') declStart--
        var declEnd = property.textRange.endOffset
        // If there's a getter on subsequent lines, include it
        if (getter != null) {
            declEnd = getter.textRange.endOffset
        }
        if (declEnd < text.length && text[declEnd] == '\n') declEnd++

        val indent = RefactoringUtils.indentationAt(text, line)
        edits.add(TextEdit(filePath, declStart, declEnd - declStart, "$indent$funText\n"))

        // Update all references: `obj.prop` -> `obj.prop()`
        for (refFile in analysis.files) {
            val refFilePath = RefactoringUtils.filePath(refFile)
            refFile.accept(object : KtTreeVisitorVoid() {
                override fun visitReferenceExpression(expression: KtReferenceExpression) {
                    super.visitReferenceExpression(expression)
                    if (expression is KtNameReferenceExpression) {
                        val target = analysis.bindingContext[BindingContext.REFERENCE_TARGET, expression]
                        if (target != null && target.original == descriptor.original) {
                            // Don't modify the declaration itself
                            if (RefactoringUtils.findParent<KtProperty>(expression) == property) return
                            val endOfRef = expression.textRange.endOffset
                            edits.add(TextEdit(refFilePath, endOfRef, 0, "()"))
                        }
                    }
                }
            })
        }

        return with(RefactoringUtils) { edits.sortedForApplication() }
    }
}
