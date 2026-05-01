package martin.refactoring

import martin.compiler.AnalysisResult
import martin.rewriter.TextEdit
import org.jetbrains.kotlin.psi.*
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * Extract superclass: creates an abstract superclass from selected members of a class.
 * Moves selected members up and makes the original class inherit from the new superclass.
 */
class ExtractSuperclassRefactoring(private val analysis: AnalysisResult) {

    fun extract(
        file: Path,
        line: Int,
        col: Int,
        superclassName: String,
        memberNames: List<String>,
    ): List<TextEdit> {
        val (ktFile, element) = RefactoringUtils.findElementAt(analysis, file, line, col)
        val classDecl = requireNotNull(RefactoringUtils.findParent<KtClass>(element)) { "No class found at $file:$line:$col" }

        val filePath = RefactoringUtils.filePath(ktFile)
        val edits = mutableListOf<TextEdit>()

        val body = requireNotNull(classDecl.body) { "Class has no body" }

        // Collect selected members
        val selectedFunctions = body.functions.filter { it.name in memberNames }
        val selectedProperties = body.properties.filter { it.name in memberNames }

        require(selectedFunctions.isNotEmpty() || selectedProperties.isNotEmpty()) { "No matching members found: $memberNames" }

        val packageName = ktFile.packageFqName.asString()
        val packageDecl = if (packageName.isNotEmpty()) "package $packageName\n\n" else ""
        val indent = RefactoringUtils.INDENT

        val memberTexts = mutableListOf<String>()
        for (func in selectedFunctions) {
            // Make abstract in superclass
            val params = func.valueParameterList?.text ?: "()"
            val returnType = func.typeReference?.let { ": ${it.text}" } ?: ""
            memberTexts.add("${indent}abstract fun ${func.name}$params$returnType")
        }
        for (prop in selectedProperties) {
            val typeText = prop.typeReference?.text ?: RefactoringUtils.FALLBACK_TYPE
            memberTexts.add("${indent}abstract val ${prop.name}: $typeText")
        }

        val superclassContent = "${packageDecl}abstract class $superclassName {\n${memberTexts.joinToString("\n")}\n}\n"

        val superclassFile = filePath.parent.resolve("$superclassName.kt")
        if (!superclassFile.parent.exists()) superclassFile.parent.createDirectories()
        superclassFile.writeText(superclassContent)

        val superTypeList = classDecl.getSuperTypeList()
        if (superTypeList != null) {
            val endOfSupertypes = superTypeList.textRange.endOffset
            edits.add(TextEdit(filePath, endOfSupertypes, 0, ", $superclassName()"))
        } else {
            val insertAfter = requireNotNull(classDecl.primaryConstructor?.textRange?.endOffset
                ?: classDecl.nameIdentifier?.textRange?.endOffset) { "Cannot determine where to add supertype" }
            edits.add(TextEdit(filePath, insertAfter, 0, " : $superclassName()"))
        }

        for (func in selectedFunctions) {
            if (!func.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.OVERRIDE_KEYWORD)) {
                edits.add(TextEdit(filePath, func.textOffset, 0, "override "))
            }
        }
        for (prop in selectedProperties) {
            if (!prop.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.OVERRIDE_KEYWORD)) {
                edits.add(TextEdit(filePath, prop.textOffset, 0, "override "))
            }
        }

        return with(RefactoringUtils) { edits.sortedForApplication() }
    }
}
