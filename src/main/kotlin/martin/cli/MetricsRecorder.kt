package martin.cli

import com.github.ajalt.clikt.core.CliktCommand
import martin.compiler.AnalysisResult
import martin.compiler.KotlinAnalyzer
import martin.daemon.DaemonClient
import martin.daemon.DaemonRequest
import martin.metrics.MetricsStore
import martin.metrics.RefactoringEvent
import martin.rewriter.SourceRewriter
import martin.rewriter.TextEdit
import java.nio.file.Path


/**
 * Runs the full analyze → refactor → rewrite → metrics pipeline.
 *
 * If a daemon is running for the project, delegates to it for faster execution.
 * Otherwise, performs analysis directly (cold path).
 */
inline fun CliktCommand.runRefactoring(
    projectDir: Path,
    type: String,
    daemonRequest: DaemonRequest? = null,
    refactor: (AnalysisResult) -> List<TextEdit>,
) {
    // Try daemon first if a request was provided
    if (daemonRequest != null) {
        val response = DaemonClient.send(projectDir, daemonRequest)
        if (response != null) {
            if (response.success) {
                echo(response.message)
            } else {
                echo("$type failed: ${response.message}")
            }
            return
        }
        // No daemon running — fall through to direct mode
    }

    val start = System.currentTimeMillis()
    val analyzer = KotlinAnalyzer.create(projectDir)
    val analysis = analyzer.analyze()
    try {
        val edits = refactor(analysis)
        val filesModified = if (edits.isNotEmpty()) SourceRewriter.applyEdits(edits) else 0
        val duration = System.currentTimeMillis() - start
        recordMetrics(projectDir, type, true, duration, filesModified, edits.size)
        echo("$type: ${edits.size} edits across $filesModified files (${duration}ms)")
    } catch (e: Exception) {
        val duration = System.currentTimeMillis() - start
        recordMetrics(projectDir, type, false, duration, 0, 0, e.message)
        throw e
    }
}

@PublishedApi
internal fun recordMetrics(
    projectDir: Path,
    type: String,
    success: Boolean,
    durationMs: Long,
    filesModified: Int,
    editsCount: Int,
    error: String? = null,
) {
    try {
        MetricsStore(projectDir).record(
            RefactoringEvent(
                type = type,
                success = success,
                durationMs = durationMs,
                filesModified = filesModified,
                editsCount = editsCount,
                error = error,
            )
        )
    } catch (_: Exception) {
        // Metrics failure should never break the refactoring
    }
}
