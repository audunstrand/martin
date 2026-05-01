package martin.refactoring

import martin.compiler.AnalysisResult
import martin.rewriter.TextEdit
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import java.nio.file.Path

/**
 * Encapsulate field: makes a public var private and generates a getter/setter.
 * `var name: String = "default"` ->
 * `private var _name: String = "default"`
 * `val name: String get() = _name`
 * `fun setName(value: String) { _name = value }`
 * Updates all external references.
 */
class EncapsulateFieldRefactoring(private val analysis: AnalysisResult) {

    fun encapsulate(file: Path, line: Int, col: Int): List<TextEdit> {
        val (ktFile, element) = RefactoringUtils.findElementAt(analysis, file, line, col)
        val property = requireNotNull(RefactoringUtils.findParent<KtProperty>(element)) { "No property found at $file:$line:$col" }

        require(property.isVar) { "Property must be var to encapsulate" }

        val name = requireNotNull(property.name) { "Property has no name" }
        val typeText = property.typeReference?.text ?: RefactoringUtils.FALLBACK_TYPE
        val backingName = "_$name"

        val descriptor = requireNotNull(analysis.bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, property]) { "Cannot resolve property" }

        val filePath = RefactoringUtils.filePath(ktFile)
        val text = ktFile.text
        val indent = RefactoringUtils.indentationAt(text, line)
        val edits = mutableListOf<TextEdit>()

        var declStart = property.textOffset
        while (declStart > 0 && text[declStart - 1] != '\n') declStart--
        var declEnd = property.textRange.endOffset
        if (declEnd < text.length && text[declEnd] == '\n') declEnd++

        val initializer = property.initializer?.text
        val initPart = if (initializer != null) " = $initializer" else ""
        val setter = "fun set${name.replaceFirstChar { it.uppercase() }}(value: $typeText) { $backingName = value }"

        val replacement = buildString {
            append("${indent}private var $backingName: $typeText$initPart\n")
            append("${indent}val $name: $typeText get() = $backingName\n")
            append("$indent$setter\n")
        }

        edits.add(TextEdit(filePath, declStart, declEnd - declStart, replacement))

        for (refFile in analysis.files) {
            val refFilePath = RefactoringUtils.filePath(refFile)
            refFile.accept(object : KtTreeVisitorVoid() {
                override fun visitBinaryExpression(expression: KtBinaryExpression) {
                    super.visitBinaryExpression(expression)
                    if (expression.operationToken == org.jetbrains.kotlin.lexer.KtTokens.EQ) {
                        val left = expression.left as? KtDotQualifiedExpression ?: return
                        val selector = left.selectorExpression as? KtNameReferenceExpression ?: return
                        val target = analysis.bindingContext[BindingContext.REFERENCE_TARGET, selector]
                        if (target != null && target.original == descriptor.original) {
                            val receiver = left.receiverExpression.text
                            val value = expression.right?.text ?: return
                            val setterCall = "$receiver.set${name.replaceFirstChar { it.uppercase() }}($value)"
                            edits.add(TextEdit(refFilePath, expression.textOffset, expression.textLength, setterCall))
                        }
                    }
                }
            })
        }

        return with(RefactoringUtils) { edits.sortedForApplication() }
    }
}
