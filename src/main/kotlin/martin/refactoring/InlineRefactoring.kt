package martin.refactoring

import martin.compiler.AnalysisResult
import martin.rewriter.TextEdit
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.VariableDescriptor
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DescriptorToSourceUtils
import java.nio.file.Path

/**
 * Inline refactoring: inlines a variable or function at a given location.
 *
 * For variables: replaces all usages with the initializer expression, removes the declaration.
 * For functions: replaces all call sites with the function body.
 */
class InlineRefactoring(private val analysis: AnalysisResult) {

data class SourceLocation(val file: Path, val line: Int, val col: Int)

    fun inline(sourceLocation: SourceLocation): List<TextEdit> {
        val (ktFile, rawElement) = RefactoringUtils.findElementAt(analysis, sourceLocation.file, sourceLocation.line, sourceLocation.col)
        val text = ktFile.text
        val elementAtCursor = requireNotNull(rawElement) { "No element found at $sourceLocation.file:$sourceLocation.line:$sourceLocation.col" }

        val declaration = requireNotNull(findInlineableDeclaration(elementAtCursor)) { "No inlineable declaration found at $sourceLocation.file:$sourceLocation.line:$sourceLocation.col" }

        return when (declaration) {
            is KtProperty -> inlineVariable(declaration)
            is KtNamedFunction -> inlineFunction(declaration)
            else -> error("Cannot inline ${declaration::class.simpleName}")
        }
    }

    private fun inlineVariable(property: KtProperty): List<TextEdit> {
        val initializer = requireNotNull(property.initializer) { "Cannot inline property without initializer: ${property.name}" }
        val initializerText = initializer.text

        val descriptor = requireNotNull(analysis.bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, property]) { "Could not resolve property: ${property.name}" }

        val references = RefactoringUtils.findAllReferences(analysis, descriptor)
        val edits = mutableListOf<TextEdit>()

        for (ref in references) {
            val refFile = ref.containingFile as KtFile
            val filePath = RefactoringUtils.filePath(refFile)

            // Check if the reference is inside a string template entry (e.g. "$varName" or "${varName}")
            val stringTemplateEntry = ref.parent as? KtStringTemplateEntry
            if (stringTemplateEntry != null) {
                // The reference is inside a string template - we need special handling
                // Replace the template entry (e.g. $bodyIndent or ${bodyIndent}) with the value
                // If the initializer is a string literal, splice its content directly into the string
                if (initializer is KtStringTemplateExpression) {
                    // String literal: extract the content between quotes and splice it in
                    val entries = initializer.entries
                    val innerText = entries.joinToString("") { it.text }
                    edits.add(TextEdit(filePath, stringTemplateEntry.textOffset, stringTemplateEntry.textLength, innerText))
                } else {
                    // Non-string initializer: wrap in ${} expression
                    val exprText = "\${${initializerText}}"
                    edits.add(TextEdit(filePath, stringTemplateEntry.textOffset, stringTemplateEntry.textLength, exprText))
                }
            } else {
                // Normal code context
                val replacement = if (needsParentheses(initializer, ref)) "($initializerText)" else initializerText
                edits.add(TextEdit(filePath, ref.textOffset, ref.textLength, replacement))
            }
        }

        edits.add(RefactoringUtils.removeElementLines(property))

        return with(RefactoringUtils) { edits.sortedForApplication() }
    }

    private fun inlineFunction(function: KtNamedFunction): List<TextEdit> {
        val body = requireNotNull(function.bodyExpression) { "Cannot inline function without body: ${function.name}" }

        val descriptor = requireNotNull(analysis.bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, function]) { "Could not resolve function: ${function.name}" }

        // Get the body text - handle block vs expression body
        val isExpressionBody = function.hasBody() && !function.hasBlockBody()
        val bodyText = if (isExpressionBody) {
            body.text
        } else {
            // For block body, if it's a single return statement, use just the expression
            val block = body as? KtBlockExpression
            val statements = block?.statements ?: emptyList()
            if (statements.size == 1) {
                val stmt = statements[0]
                if (stmt is KtReturnExpression) {
                    stmt.returnedExpression?.text ?: "Unit"
                } else {
                    stmt.text
                }
            } else {
                // Multi-statement body: wrap in run { }
                "run {\n${statements.joinToString("\n") { "    ${it.text}" }}\n}"
            }
        }

        val params = function.valueParameters
        val references = RefactoringUtils.findAllCallSites(analysis, descriptor as FunctionDescriptor)
        val edits = mutableListOf<TextEdit>()

        for (callExpr in references) {
            var replacement = bodyText

            // Substitute parameters with arguments
            val args = callExpr.valueArguments
                for ((i, param) in params.withIndex()) {
                    val paramName = param.name ?: continue
                    val argText = if (i < args.size) {
                        args[i].getArgumentExpression()?.text ?: paramName
                    } else {
                        // Use default value if available
                        param.defaultValue?.text ?: paramName
                    }
                    replacement = replacement.replace(Regex("\\b${Regex.escape(paramName)}\\b"), argText)
                }

            // Determine the full expression to replace (might be a dot-qualified expression)
            val replaceElement = callExpr.parent.let {
                if (it is KtDotQualifiedExpression && it.selectorExpression == callExpr) it else callExpr
            }

            val refFile = replaceElement.containingFile as KtFile
            val filePath = RefactoringUtils.filePath(refFile)
            edits.add(TextEdit(filePath, replaceElement.textOffset, replaceElement.textLength, replacement))
        }

        edits.add(RefactoringUtils.removeElementLines(function))

        return with(RefactoringUtils) { edits.sortedForApplication() }
    }

    private fun needsParentheses(initializer: KtExpression, usageSite: PsiElement): Boolean {
        // Binary expressions used inside other binary expressions may need parens
        if (initializer is KtBinaryExpression) {
            val parent = usageSite.parent
            if (parent is KtBinaryExpression || parent is KtDotQualifiedExpression) {
                return true
            }
        }
        return false
    }

    private fun findInlineableDeclaration(element: PsiElement): KtDeclaration? {
        var current: PsiElement? = element
        while (current != null) {
            when (current) {
                is KtProperty -> return current
                is KtNamedFunction -> return current
                is KtNameReferenceExpression -> {
                    // Resolve to the declaration
                    val target = analysis.bindingContext[BindingContext.REFERENCE_TARGET, current]
                    if (target != null) {
                        val decl = DescriptorToSourceUtils.descriptorToDeclaration(target)
                        if (decl is KtProperty || decl is KtNamedFunction) return decl as KtDeclaration
                    }
                }
            }
            current = current.parent
        }
        return null
    }

}
