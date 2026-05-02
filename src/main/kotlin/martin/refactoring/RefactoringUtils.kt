package martin.refactoring

import martin.compiler.AnalysisResult
import martin.compiler.KotlinAnalyzer.Companion.ORIGINAL_FILE_KEY
import martin.rewriter.TextEdit
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DescriptorToSourceUtils
import java.nio.file.Path

/**
 * Shared utilities for refactoring implementations.
 */
object RefactoringUtils {

    const val INDENT = "    "
    const val FALLBACK_TYPE = "Any"

    fun findKtFile(analysis: AnalysisResult, file: Path): KtFile {
        val absoluteFile = file.toAbsolutePath().normalize()
        return requireNotNull(analysis.files.find { ktFile ->
            val originalPath = ktFile.getUserData(ORIGINAL_FILE_KEY)
            originalPath != null && originalPath.toAbsolutePath().normalize() == absoluteFile
        }) { "File not found in analysis: $file" }
    }

    /**
     * Locate the PSI element at a given file/line/col, returning the KtFile and element.
     */
    fun findElementAt(analysis: AnalysisResult, file: Path, line: Int, col: Int): Pair<KtFile, PsiElement?> {
        val ktFile = findKtFile(analysis, file)
        val offset = lineColToOffset(ktFile.text, line, col)
        return ktFile to ktFile.findElementAt(offset)
    }

    fun lineColToOffset(text: String, line: Int, col: Int): Int {
        var currentLine = 1
        var lineStart = 0
        for ((i, ch) in text.withIndex()) {
            if (currentLine == line) {
                return lineStart + col - 1
            }
            if (ch == '\n') {
                currentLine++
                lineStart = i + 1
            }
        }
        if (currentLine == line) {
            return lineStart + col - 1
        }
        return text.length
    }

    fun lineToOffset(text: String, line: Int): Int {
        var currentLine = 1
        var lineStart = 0
        for ((i, ch) in text.withIndex()) {
            if (currentLine == line) return lineStart
            if (ch == '\n') {
                currentLine++
                lineStart = i + 1
            }
        }
        if (currentLine == line) return lineStart
        return text.length
    }

    fun endOfLineOffset(text: String, line: Int): Int {
        var currentLine = 1
        for ((i, ch) in text.withIndex()) {
            if (currentLine == line && ch == '\n') return i
            if (ch == '\n') currentLine++
        }
        return text.length
    }

    fun offsetToLineCol(text: String, offset: Int): Pair<Int, Int> {
        var line = 1
        var lineStart = 0
        for ((i, ch) in text.withIndex()) {
            if (i == offset) return Pair(line, i - lineStart + 1)
            if (ch == '\n') {
                line++
                lineStart = i + 1
            }
        }
        return Pair(line, offset - lineStart + 1)
    }

    fun filePath(ktFile: KtFile): Path = checkNotNull(ktFile.getUserData(ORIGINAL_FILE_KEY)) { "No original file path for ${ktFile.name}" }

    /**
     * Find the indentation string of a given line in source text.
     */
    fun indentationAt(text: String, line: Int): String {
        val lineStart = lineToOffset(text, line)
        return text.substring(lineStart).takeWhile { it == ' ' || it == '\t' }
    }

    /**
     * Create a TextEdit that removes the full line(s) containing the given PSI element.
     */
    fun removeElementLines(element: PsiElement): TextEdit {
        val ktFile = element.containingFile as KtFile
        val text = ktFile.text
        var start = element.textOffset
        while (start > 0 && text[start - 1] != '\n') start--
        var end = element.textRange.endOffset
        if (end < text.length && text[end] == '\n') end++
        return TextEdit(filePath = filePath(ktFile), offset = start, length = end - start, replacement = "")
    }

    /**
     * Walk up the PSI tree to find a parent of a specific type.
     */
    inline fun <reified T : PsiElement> findParent(element: PsiElement?): T? {
        var current = element
         while (current != null) {
            if (current is T) return current
            current = current.parent
        }
        return null
    }

    /**
     * Standard sort order for edits: group by file, then descending offset
     * so that later edits in a file are applied first (avoiding offset shifts).
     */
    fun List<TextEdit>.sortedForApplication(): List<TextEdit> =
        sortedWith(compareBy({ it.filePath.toString() }, { -it.offset }))

    /**
     * Insert a declaration into the companion object of a class,
     * creating the companion object if it doesn't exist.
     */
    fun insertIntoCompanionObject(
        classDecl: KtClassOrObject,
        declaration: String,
        filePath: Path,
        edits: MutableList<TextEdit>,
    ) {
        val companionObject = classDecl.companionObjects.firstOrNull()
        if (companionObject != null) {
            val body = companionObject.body
            if (body != null) {
                val indent = INDENT.repeat(2)
                val insertOffset = body.textOffset + 1
                edits.add(TextEdit(filePath, insertOffset, 0, "\n$indent$declaration"))
            }
        } else {
            val classBody = classDecl.body
            if (classBody != null) {
                val companionText = "\n${INDENT}companion object {\n${INDENT}${INDENT}$declaration\n${INDENT}}\n"
                val insertOffset = classBody.textRange.endOffset - 1
                edits.add(TextEdit(filePath, insertOffset, 0, companionText))
            }
        }
    }

    /**
     * Find all name reference expressions that resolve to the given descriptor.
     */
    fun findAllReferences(analysis: AnalysisResult, descriptor: DeclarationDescriptor): List<KtNameReferenceExpression> {
        val results = mutableListOf<KtNameReferenceExpression>()
        for (ktFile in analysis.files) {
            ktFile.accept(object : KtTreeVisitorVoid() {
                override fun visitReferenceExpression(expression: KtReferenceExpression) {
                    super.visitReferenceExpression(expression)
                    val target = analysis.bindingContext[BindingContext.REFERENCE_TARGET, expression]
                    if (target != null && matchesDescriptor(target, descriptor)) {
                        if (expression is KtNameReferenceExpression) {
                            results.add(expression)
                        }
                    }
                }
            })
        }
        return results
    }

    /**
     * Check if a resolved target matches the given descriptor.
     * Handles the case where the target is a constructor but we're looking for the class.
     */
    fun matchesDescriptor(target: DeclarationDescriptor, descriptor: DeclarationDescriptor): Boolean {
        if (target.original == descriptor.original) return true
        // Constructor calls resolve to ConstructorDescriptor; match against the containing class
        if (target is ConstructorDescriptor && descriptor is ClassDescriptor) {
            return target.constructedClass.original == descriptor.original
        }
        if (descriptor is ConstructorDescriptor && target is ClassDescriptor) {
            return descriptor.constructedClass.original == target.original
        }
        return false
    }

    /**
     * Find all references including the declaration's own name identifier.
     */
    fun findAllReferencesWithDeclaration(analysis: AnalysisResult, descriptor: DeclarationDescriptor): List<PsiElement> {
        val results = mutableListOf<PsiElement>()
        val declarationPsi = DescriptorToSourceUtils.descriptorToDeclaration(descriptor)
        if (declarationPsi is KtNamedDeclaration) {
            declarationPsi.nameIdentifier?.let { results.add(it) }
        }
        results.addAll(findAllReferences(analysis, descriptor))
        return results
    }

    /**
     * Find all call expressions that resolve to the given function descriptor.
     */
    fun findAllCallSites(analysis: AnalysisResult, descriptor: FunctionDescriptor): List<KtCallExpression> {
        val results = mutableListOf<KtCallExpression>()
        for (ktFile in analysis.files) {
            ktFile.accept(object : KtTreeVisitorVoid() {
                override fun visitCallExpression(expression: KtCallExpression) {
                    super.visitCallExpression(expression)
                    val callee = expression.calleeExpression as? KtReferenceExpression ?: return
                    val resolved = analysis.bindingContext[BindingContext.REFERENCE_TARGET, callee]
                    if (resolved != null && resolved.original == descriptor.original) {
                        results.add(expression)
                    }
                }
            })
        }
        return results
    }
}
