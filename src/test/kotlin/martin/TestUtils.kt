package martin

import martin.compiler.AnalysisResult
import martin.compiler.KotlinAnalyzer
import martin.rewriter.SourceRewriter
import martin.rewriter.TextEdit
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

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
