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

        // Refuse to inline a var that is reassigned
        if (property.isVar) {
            require(!isReassigned(property)) {
                "Cannot inline var '${property.name}': it is reassigned after initialization"
            }
        }

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

            // Build parameter name -> argument text mapping, handling named arguments
            val args = callExpr.valueArguments
            val paramMap = mutableMapOf<String, String>()
            for ((i, arg) in args.withIndex()) {
                val argExpr = arg.getArgumentExpression()?.text ?: continue
                val argName = arg.getArgumentName()?.asName?.asString()
                if (argName != null) {
                    // Named argument
                    paramMap[argName] = argExpr
                } else {
                    // Positional argument
                    val paramName = params.getOrNull(i)?.name ?: continue
                    paramMap[paramName] = argExpr
                }
            }
            // Fill in defaults for missing params
            for (param in params) {
                val name = param.name ?: continue
                if (name !in paramMap) {
                    paramMap[name] = param.defaultValue?.text ?: name
                }
            }

            // Replace parameter references using PSI-aware approach:
            // Process replacements from right to left to avoid offset issues
            val bodyPsi = function.bodyExpression
            if (bodyPsi != null && paramMap.isNotEmpty()) {
                val bodyOffset = bodyPsi.textOffset
                data class Replacement(val start: Int, val end: Int, val text: String)
                val replacements = mutableListOf<Replacement>()

                bodyPsi.accept(object : KtTreeVisitorVoid() {
                    override fun visitReferenceExpression(expression: KtReferenceExpression) {
                        super.visitReferenceExpression(expression)
                        if (expression !is KtNameReferenceExpression) return
                        // Skip references inside string template entries (handled separately)
                        if (expression.parent is KtSimpleNameStringTemplateEntry) return
                        val refName = expression.getReferencedName()
                        val argText = paramMap[refName] ?: return
                        // Check it actually resolves to a parameter
                        val target = analysis.bindingContext[BindingContext.REFERENCE_TARGET, expression]
                        if (target is org.jetbrains.kotlin.descriptors.ValueParameterDescriptor) {
                            val relStart = expression.textOffset - bodyOffset
                            val relEnd = relStart + expression.textLength
                            replacements.add(Replacement(relStart, relEnd, argText))
                        }
                    }

                    override fun visitSimpleNameStringTemplateEntry(entry: KtSimpleNameStringTemplateEntry) {
                        super.visitSimpleNameStringTemplateEntry(entry)
                        val refExpr = entry.expression as? KtNameReferenceExpression ?: return
                        val refName = refExpr.getReferencedName()
                        val argText = paramMap[refName] ?: return
                        val target = analysis.bindingContext[BindingContext.REFERENCE_TARGET, refExpr]
                        if (target is org.jetbrains.kotlin.descriptors.ValueParameterDescriptor) {
                            val relStart = entry.textOffset - bodyOffset
                            val relEnd = relStart + entry.textLength
                            // For string template, wrap in ${} if the arg isn't a simple name
                            val templateReplacement = if (argText.matches(Regex("\\w+"))) "\$$argText" else "\${$argText}"
                            replacements.add(Replacement(relStart, relEnd, templateReplacement))
                        }
                    }
                })

                // Now apply replacements to bodyText from right to left
                // But bodyText may differ from bodyPsi.text (e.g. single return extracted)
                // Use simple regex fallback if PSI offsets don't align with bodyText
                if (replacements.isNotEmpty()) {
                    // Try to use the direct body text approach
                    val fullBodyText = bodyPsi.text
                    val sorted = replacements.sortedByDescending { it.start }
                    var result = fullBodyText
                    for (r in sorted) {
                        if (r.start >= 0 && r.end <= result.length) {
                            result = result.substring(0, r.start) + r.text + result.substring(r.end)
                        }
                    }
                    // Now extract the same way bodyText was extracted
                    replacement = if (isExpressionBody) {
                        result
                    } else {
                        val block = bodyPsi as? KtBlockExpression
                        val statements = block?.statements ?: emptyList()
                        if (statements.size == 1) {
                            val stmt = statements[0]
                            val stmtRelStart = stmt.textOffset - bodyOffset
                            val stmtRelEnd = stmtRelStart + stmt.textLength
                            // Extract the corresponding portion from result
                            if (stmt is KtReturnExpression) {
                                val retExpr = stmt.returnedExpression
                                if (retExpr != null) {
                                    val retRelStart = retExpr.textOffset - bodyOffset
                                    val retRelEnd = retRelStart + retExpr.textLength
                                    if (retRelStart >= 0 && retRelEnd <= result.length) {
                                        result.substring(retRelStart, retRelEnd)
                                    } else replacement
                                } else "Unit"
                            } else {
                                if (stmtRelStart >= 0 && stmtRelEnd <= result.length) {
                                    result.substring(stmtRelStart, stmtRelEnd)
                                } else replacement
                            }
                        } else {
                            "run {\n${statements.joinToString("\n") { s ->
                                val sStart = s.textOffset - bodyOffset
                                val sEnd = sStart + s.textLength
                                if (sStart >= 0 && sEnd <= result.length) {
                                    "    ${result.substring(sStart, sEnd)}"
                                } else "    ${s.text}"
                            }}\n}"
                        }
                    }
                }
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

    private fun isReassigned(property: KtProperty): Boolean {
        val name = property.name ?: return false
        val descriptor = analysis.bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, property] ?: return false
        val containingBlock = property.parent ?: return false
        var found = false
        containingBlock.accept(object : KtTreeVisitorVoid() {
            override fun visitBinaryExpression(expression: KtBinaryExpression) {
                super.visitBinaryExpression(expression)
                if (found) return
                val op = expression.operationToken.toString()
                if (op == "EQ" || op == "PLUSEQ" || op == "MINUSEQ" || op == "MULTEQ" || op == "DIVEQ") {
                    val left = expression.left as? KtNameReferenceExpression ?: return
                    val target = analysis.bindingContext[BindingContext.REFERENCE_TARGET, left]
                    if (target?.original == descriptor.original) {
                        found = true
                    }
                }
            }
        })
        return found
    }

}
