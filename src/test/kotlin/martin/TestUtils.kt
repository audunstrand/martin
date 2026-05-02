package martin

import martin.compiler.AnalysisResult
import martin.compiler.KotlinAnalyzer
import martin.rewriter.SourceRewriter
import martin.rewriter.TextEdit
import org.jetbrains.kotlin.diagnostics.Severity
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.fail

fun createProject(tempDir: Path, files: Map<String, String>): Path {
    val srcDir = tempDir.resolve("src/main/kotlin")
    srcDir.createDirectories()
    for ((name, content) in files) {
        srcDir.resolve(name).also { it.parent.createDirectories() }.writeText(content)
    }
    return tempDir
}

fun analyzeProject(projectDir: Path): AnalysisResult =
    KotlinAnalyzer.create(projectDir).analyze()

fun applyAndRead(edits: List<TextEdit>, file: Path): String {
    check(edits.isNotEmpty()) { "Expected non-empty edits" }
    SourceRewriter.applyEdits(edits)
    return file.readText()
}

/**
 * Re-analyzes the project after refactoring and checks for compilation errors.
 * Fails the test if any ERROR-level diagnostics are found.
 */
fun assertCompiles(projectDir: Path, context: String = "") {
    val analysis = analyzeProject(projectDir)
    val errors = analysis.bindingContext.diagnostics.all()
        .filter { it.severity == Severity.ERROR }
        .map { diagnostic ->
            val psi = diagnostic.psiElement
            val file = psi.containingFile?.name ?: "unknown"
            val text = psi.text.take(50)
            "$file: ${diagnostic.factory.name} — `$text`"
        }
        .toList()

    if (errors.isNotEmpty()) {
        val prefix = if (context.isNotEmpty()) "$context\n" else ""
        fail("${prefix}Refactored code has ${errors.size} compilation error(s):\n${errors.joinToString("\n")}")
    }
}
