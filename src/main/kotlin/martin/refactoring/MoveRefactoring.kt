package martin.refactoring

import martin.compiler.AnalysisResult
import martin.rewriter.TextEdit
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * Move refactoring: moves a top-level declaration to a different package/file.
 *
 * - Moves the declaration text to the target file
 * - Updates the package declaration in the target file
 * - Adds imports in all files that reference the moved symbol
 * - Removes the declaration from the source file
 */
class MoveRefactoring(private val analysis: AnalysisResult) {

    /**
     * Move a top-level symbol to a target package.
     *
     * @param symbolFqn Fully qualified name of the symbol to move (e.g., "com.example.MyClass")
     * @param toPackage Target package name (e.g., "com.other")
     * @param sourceRoots Source roots to determine where to create the target file
     */
    fun move(symbolFqn: String, toPackage: String, sourceRoots: List<Path>): List<TextEdit> {
        val parts = symbolFqn.split(".")
        val symbolName = parts.last()
        val sourcePackage = parts.dropLast(1).joinToString(".")

        val declaration = requireNotNull(findDeclarationByFqn(symbolFqn)) { "Symbol not found: $symbolFqn" }

        val sourceFile = declaration.containingFile as KtFile
        val sourceFilePath = RefactoringUtils.filePath(sourceFile)
        val sourceText = sourceFile.text

        val declarationText = declaration.text

        // Determine the descriptor for finding references
        val descriptor = requireNotNull(analysis.bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, declaration]) { "Could not resolve descriptor for: $symbolFqn" }

        val edits = mutableListOf<TextEdit>()

        edits.add(RefactoringUtils.removeElementLines(declaration))

        // Create or append to the target file
        val targetDir = sourceRoots.first().resolve(toPackage.replace(".", "/"))
        if (!targetDir.exists()) {
            targetDir.createDirectories()
        }
        val targetFilePath = targetDir.resolve("$symbolName.kt")

        val newFqn = "$toPackage.$symbolName"

        if (targetFilePath.exists()) {
            // Append to existing file
            val existingContent = targetFilePath.toFile().readText()
            val appendText = "\n\n$declarationText\n"
            edits.add(TextEdit(targetFilePath, existingContent.length, 0, appendText))
        } else {
            // Create new file with package declaration
            val newFileContent = "package $toPackage\n\n$declarationText\n"
            targetFilePath.writeText(newFileContent)
        }

        // Update imports in all referencing files
        val references = RefactoringUtils.findAllReferences(analysis, descriptor)
        val filesNeedingImport = references
            .map { it.containingFile as KtFile }
            .distinctBy { RefactoringUtils.filePath(it) }
            .filter { ktFile ->
                // Don't add import to the target file itself
                val filePath = RefactoringUtils.filePath(ktFile)
                filePath != targetFilePath
            }

        for (ktFile in filesNeedingImport) {
            val filePath = RefactoringUtils.filePath(ktFile)
            val fileText = ktFile.text

            // Check if there's already an import for the old FQN
            val oldImport = "import $symbolFqn"
            val newImport = "import $newFqn"

            val oldImportIdx = fileText.indexOf(oldImport)
            if (oldImportIdx >= 0) {
                edits.add(TextEdit(filePath, oldImportIdx, oldImport.length, newImport))
            } else {
                // Need to add import - find the import section
                val importList = ktFile.importList
                if (importList != null && importList.imports.isNotEmpty()) {
                    val lastImport = importList.imports.last()
                    val insertOffset = lastImport.textRange.endOffset
                    edits.add(TextEdit(filePath, insertOffset, 0, "\n$newImport"))
                } else {
                    val packageDirective = ktFile.packageDirective
                    if (packageDirective != null && packageDirective.text.isNotEmpty()) {
                        val insertOffset = packageDirective.textRange.endOffset
                        edits.add(TextEdit(filePath, insertOffset, 0, "\n\n$newImport"))
                    } else {
                        edits.add(TextEdit(filePath, 0, 0, "$newImport\n\n"))
                    }
                }
            }
        }

        return with(RefactoringUtils) { edits.sortedForApplication() }
    }

    private fun findDeclarationByFqn(fqn: String): KtNamedDeclaration? {
        val parts = fqn.split(".")
        val name = parts.last()
        val packageName = parts.dropLast(1).joinToString(".")

        for (ktFile in analysis.files) {
            val filePackage = ktFile.packageFqName.asString()
            if (filePackage != packageName) continue

            for (decl in ktFile.declarations) {
                if (decl is KtNamedDeclaration && decl.name == name) {
                    return decl
                }
            }
        }
        return null
    }

}
