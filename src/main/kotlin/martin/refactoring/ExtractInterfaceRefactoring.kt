package martin.refactoring

import martin.compiler.AnalysisResult
import martin.rewriter.TextEdit
import org.jetbrains.kotlin.psi.*
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * Extract interface: creates an interface from selected methods of a class.
 * Makes the class implement the new interface.
 */
class ExtractInterfaceRefactoring(private val analysis: AnalysisResult) {

    fun extract(
        file: Path,
        line: Int,
        col: Int,
        interfaceName: String,
        methodNames: List<String>,
    ): List<TextEdit> {
        val (ktFile, element) = RefactoringUtils.findElementAt(analysis, file, line, col)
        val classDecl = requireNotNull(RefactoringUtils.findParent<KtClass>(element)) { "No class found at $file:$line:$col" }

        val filePath = RefactoringUtils.filePath(ktFile)
        val edits = mutableListOf<TextEdit>()

        // Collect method signatures
        val methods = classDecl.body?.functions?.filter { it.name in methodNames } ?: emptyList()
        require(methods.isNotEmpty()) { "No matching methods found: $methodNames" }

        val interfaceMethods = methods.joinToString("\n    ") { func ->
            val params = func.valueParameterList?.text ?: "()"
            val returnType = func.typeReference?.let { ": ${it.text}" } ?: ""
            "fun ${func.name}$params$returnType"
        }

        // Determine the package
        val packageName = ktFile.packageFqName.asString()
        val packageDecl = if (packageName.isNotEmpty()) "package $packageName\n\n" else ""

        val interfaceContent = "${packageDecl}interface $interfaceName {\n    $interfaceMethods\n}\n"

        val interfaceFile = filePath.parent.resolve("$interfaceName.kt")
        if (!interfaceFile.parent.exists()) interfaceFile.parent.createDirectories()
        interfaceFile.writeText(interfaceContent)

        val superTypeList = classDecl.getSuperTypeList()
        if (superTypeList != null) {
            val endOfSupertypes = superTypeList.textRange.endOffset
            edits.add(TextEdit(filePath, endOfSupertypes, 0, ", $interfaceName"))
        } else {
            // No supertypes yet - add after class name/primary constructor
            val insertAfter = requireNotNull(classDecl.primaryConstructor?.textRange?.endOffset
                ?: classDecl.nameIdentifier?.textRange?.endOffset) { "Cannot determine where to add supertype" }
            edits.add(TextEdit(filePath, insertAfter, 0, " : $interfaceName"))
        }

        for (func in methods) {
            if (!func.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.OVERRIDE_KEYWORD)) {
                edits.add(TextEdit(filePath, func.textOffset, 0, "override "))
            }
        }

        return with(RefactoringUtils) { edits.sortedForApplication() }
    }
}
