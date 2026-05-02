package martin.refactoring.core

import martin.compiler.AnalysisResult
import martin.compiler.GradleProjectDiscovery
import martin.refactoring.*
import martin.rewriter.TextEdit
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

/**
 * Move refactoring: moves a top-level declaration to a different package/file.
 *
 * Returns edits for existing files and new file contents via [RefactoringOutput.newFiles].
 */
class MoveRefactoring(private val analysis: AnalysisResult) : Refactoring {

    override val name = "move"
    override val description = "Move a top-level declaration to a different package. Updates all imports across the project"
    override val params = listOf(
        ParamDef("symbol", ParamType.STRING, "Fully qualified name of the symbol to move (e.g. 'com.example.MyClass')"),
        ParamDef("toPackage", ParamType.STRING, "Target package name (e.g. 'com.other')"),
        ParamDef("sourceRoots", ParamType.STRING, "Comma-separated source root paths", required = false),
    )

    override fun execute(ctx: RefactoringContext): RefactoringOutput {
        val sourceRoots = ctx.stringOrNull("sourceRoots")
            ?.split(",")?.map { Path.of(it.trim()) }
            ?: GradleProjectDiscovery(ctx.projectDir).discoverSourceRoots()
        return move(ctx.string("symbol"), ctx.string("toPackage"), sourceRoots)
    }

    fun move(symbolFqn: String, toPackage: String, sourceRoots: List<Path>): RefactoringOutput {
        val parts = symbolFqn.split(".")
        val symbolName = parts.last()
        val sourcePackage = parts.dropLast(1).joinToString(".")

        val declaration = requireNotNull(findDeclarationByFqn(symbolFqn)) { "Symbol not found: $symbolFqn" }

        val sourceFile = declaration.containingFile as KtFile
        val sourceFilePath = RefactoringUtils.filePath(sourceFile)
        val sourceText = sourceFile.text

        val declarationText = declaration.text

        val descriptor = requireNotNull(analysis.bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, declaration]) { "Could not resolve descriptor for: $symbolFqn" }

        val edits = mutableListOf<TextEdit>()
        val newFiles = mutableMapOf<Path, String>()

        edits.add(RefactoringUtils.removeElementLines(declaration))

        // Determine target file path
        val targetDir = sourceRoots.first().resolve(toPackage.replace(".", "/"))
        val targetFilePath = targetDir.resolve("$symbolName.kt")

        val newFqn = "$toPackage.$symbolName"

        if (targetFilePath.exists()) {
            // Append to existing file
            val existingContent = targetFilePath.toFile().readText()
            val appendText = "\n\n$declarationText\n"
            edits.add(TextEdit(targetFilePath, existingContent.length, 0, appendText))
        } else {
            // Create new file content — returned via newFiles instead of writing directly
            val neededImports = collectNeededImports(declaration, sourceFile, toPackage)
            val importsBlock = if (neededImports.isNotEmpty()) {
                neededImports.joinToString("\n") { "import $it" } + "\n\n"
            } else ""
            val newFileContent = "package $toPackage\n\n$importsBlock$declarationText\n"
            newFiles[targetFilePath] = newFileContent
        }

        // Update imports in all referencing files
        val references = RefactoringUtils.findAllReferences(analysis, descriptor)
        val filesNeedingImport = references
            .map { it.containingFile as KtFile }
            .distinctBy { RefactoringUtils.filePath(it) }
            .filter { ktFile ->
                val filePath = RefactoringUtils.filePath(ktFile)
                filePath != targetFilePath
            }

        for (ktFile in filesNeedingImport) {
            val filePath = RefactoringUtils.filePath(ktFile)
            val fileText = ktFile.text

            val oldImport = "import $symbolFqn"
            val newImport = "import $newFqn"

            val oldImportIdx = fileText.indexOf(oldImport)
            if (oldImportIdx >= 0) {
                edits.add(TextEdit(filePath, oldImportIdx, oldImport.length, newImport))
            } else {
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

        return RefactoringOutput(
            edits = with(RefactoringUtils) { edits.sortedForApplication() },
            newFiles = newFiles,
        )
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

    private fun collectNeededImports(
        declaration: KtNamedDeclaration,
        sourceFile: KtFile,
        targetPackage: String,
    ): List<String> {
        val sourcePackage = sourceFile.packageFqName.asString()
        val neededFqns = mutableSetOf<String>()

        declaration.accept(object : KtTreeVisitorVoid() {
            override fun visitReferenceExpression(expression: KtReferenceExpression) {
                super.visitReferenceExpression(expression)
                val target = analysis.bindingContext[BindingContext.REFERENCE_TARGET, expression]
                if (target != null) {
                    val fqn = target.fqNameSafe.asString()
                    val pkg = target.fqNameSafe.parent().asString()
                    if (pkg != targetPackage && !pkg.startsWith("kotlin") && pkg.isNotEmpty() && fqn != "${sourcePackage}.${declaration.name}") {
                        neededFqns.add(fqn)
                    }
                }
            }

            override fun visitTypeReference(typeReference: KtTypeReference) {
                super.visitTypeReference(typeReference)
                val type = analysis.bindingContext[BindingContext.TYPE, typeReference]
                if (type != null) {
                    val typeDescriptor = type.constructor.declarationDescriptor
                    if (typeDescriptor != null) {
                        val fqn = typeDescriptor.fqNameSafe.asString()
                        val pkg = typeDescriptor.fqNameSafe.parent().asString()
                        if (pkg != targetPackage && !pkg.startsWith("kotlin") && pkg.isNotEmpty()) {
                            neededFqns.add(fqn)
                        }
                    }
                }
            }
        })

        return neededFqns.sorted()
    }
}
