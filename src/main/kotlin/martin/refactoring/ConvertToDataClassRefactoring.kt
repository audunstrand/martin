package martin.refactoring

import martin.compiler.AnalysisResult
import martin.rewriter.TextEdit
import org.jetbrains.kotlin.psi.*
import java.nio.file.Path

/**
 * Convert a regular class to a data class.
 * Adds the `data` modifier if the class has a primary constructor with val/var parameters.
 */
class ConvertToDataClassRefactoring(private val analysis: AnalysisResult) {

    fun convert(file: Path, line: Int, col: Int): List<TextEdit> {
        val (ktFile, element) = RefactoringUtils.findElementAt(analysis, file, line, col)
        val classDecl = requireNotNull(RefactoringUtils.findParent<KtClass>(element)) { "No class found at $file:$line:$col" }

        require(!classDecl.isData()) { "Class is already a data class" }
        require(!classDecl.isInterface() && !classDecl.isEnum() && !classDecl.isAnnotation()) { "Cannot convert ${classDecl.getClassOrInterfaceKeyword()?.text} to data class" }

        val primaryConstructor = classDecl.primaryConstructor
        require(primaryConstructor != null && primaryConstructor.valueParameters.isNotEmpty()) { "Class must have a primary constructor with parameters to be a data class" }

        // Verify all params have val/var
        val paramsWithoutValVar = primaryConstructor.valueParameters.filter { !it.hasValOrVar() }
        val filePath = RefactoringUtils.filePath(ktFile)
        val edits = mutableListOf<TextEdit>()

        for (param in paramsWithoutValVar) {
            edits.add(TextEdit(filePath, param.textOffset, 0, "val "))
        }

        val classKeyword = requireNotNull(classDecl.getClassOrInterfaceKeyword()) { "Cannot find class keyword" }
        edits.add(TextEdit(filePath, classKeyword.textOffset, 0, "data "))

        return with(RefactoringUtils) { edits.sortedForApplication() }
    }
}
