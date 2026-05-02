package martin.cli

import com.github.ajalt.clikt.core.CliktCommand
import martin.compiler.AnalysisResult
import martin.compiler.KotlinAnalyzer
import martin.daemon.DaemonClient
import martin.daemon.DaemonRequest
import martin.metrics.MetricsStore
import martin.metrics.RefactoringEvent
import martin.refactoring.RefactoringOutput
import martin.rewriter.SourceRewriter
import kotlinx.serialization.json.Json
import java.nio.file.Path


@PublishedApi
internal val jsonPretty = Json { prettyPrint = true }

/**
 * Runs the full analyze → refactor → rewrite → metrics pipeline.
 *
 * Supports:
 * - Daemon delegation for faster execution
 * - --dry-run mode (compute edits without writing)
 * - --format json mode (structured output)
 */
inline fun CliktCommand.runRefactoring(
    projectDir: Path,
    type: String,
    daemonRequest: DaemonRequest? = null,
    refactor: (AnalysisResult) -> RefactoringOutput,
) {
    val config = globalConfig
    val isJson = config.format == "json"
    val isDryRun = config.dryRun

    // Try daemon first if a request was provided (and not in dry-run mode)
    if (daemonRequest != null && !isDryRun) {
        val response = DaemonClient.send(projectDir, daemonRequest)
        if (response != null) {
            if (isJson) {
                val result = RefactoringResult(
                    success = response.success,
                    command = type,
                    filesModified = response.filesModified,
                    durationMs = response.durationMs,
                    error = if (!response.success) response.message else null,
                )
                echo(jsonPretty.encodeToString(RefactoringResult.serializer(), result))
            } else {
                if (response.success) {
                    echo(response.message)
                } else {
                    echo("$type failed: ${response.message}")
                }
            }
            return
        }
        // No daemon running — fall through to direct mode
    }

    val start = System.currentTimeMillis()
    val analyzer = KotlinAnalyzer.create(projectDir)
    val analysis = analyzer.analyze()
    try {
        val output = refactor(analysis)
        val edits = output.edits
        val duration = System.currentTimeMillis() - start

        if (isDryRun) {
            // Dry-run: return edits without writing
            if (isJson) {
                val editInfos = edits.map { edit ->
                    RefactoringResult.EditInfo(
                        file = projectDir.relativize(edit.filePath).toString(),
                        offset = edit.offset,
                        length = edit.length,
                        replacement = edit.replacement,
                    )
                }
                val result = RefactoringResult(
                    success = true,
                    command = type,
                    edits = editInfos,
                    filesModified = edits.map { it.filePath }.distinct().size,
                    durationMs = duration,
                )
                echo(jsonPretty.encodeToString(RefactoringResult.serializer(), result))
            } else {
                val fileCount = edits.map { it.filePath }.distinct().size
                echo("$type (dry-run): ${edits.size} edits across $fileCount files (${duration}ms)")
                for (edit in edits) {
                    val relPath = projectDir.relativize(edit.filePath)
                    echo("  $relPath @ offset ${edit.offset}: replace ${edit.length} chars")
                }
            }
            recordMetrics(projectDir, type, true, duration, 0, edits.size)
        } else {
            // Normal mode: apply edits and write new files
            output.writeNewFiles()
            val filesModified = if (edits.isNotEmpty()) SourceRewriter.applyEdits(edits) else 0
            val totalFiles = filesModified + output.newFiles.size
            recordMetrics(projectDir, type, true, duration, totalFiles, edits.size)

            if (isJson) {
                val editInfos = edits.map { edit ->
                    RefactoringResult.EditInfo(
                        file = projectDir.relativize(edit.filePath).toString(),
                        offset = edit.offset,
                        length = edit.length,
                        replacement = edit.replacement,
                    )
                }
                val result = RefactoringResult(
                    success = true,
                    command = type,
                    edits = editInfos,
                    filesModified = totalFiles,
                    durationMs = duration,
                )
                echo(jsonPretty.encodeToString(RefactoringResult.serializer(), result))
            } else {
                echo("$type: ${edits.size} edits across $totalFiles files (${duration}ms)")
            }
        }
    } catch (e: Exception) {
        val duration = System.currentTimeMillis() - start
        recordMetrics(projectDir, type, false, duration, 0, 0, e.message)

        if (isJson) {
            val result = RefactoringResult(
                success = false,
                command = type,
                durationMs = duration,
                error = e.message,
            )
            echo(jsonPretty.encodeToString(RefactoringResult.serializer(), result))
        } else {
            throw e
        }
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
