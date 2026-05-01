package martin.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import kotlin.io.path.Path
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.path
import martin.metrics.MetricsStore
import martin.metrics.RefactoringSummary
import java.time.Duration
import java.time.Instant

class ReportCommand : CliktCommand(name = "report") {

    private val projectDir by option("--project", "-p")
        .path(mustExist = true, canBeFile = false)
        .default(Path("."))

    private val since by option("--since", "-s", help = "Time window: 1d, 7d, 30d, all")
        .default("all")

    private val format by option("--format", "-f", help = "Output format")
        .choice("text", "json")
        .default("text")

    override fun run() {
        val store = MetricsStore(projectDir)

        val sinceInstant: Instant? = when (since) {
            "1d" -> Instant.now().minus(Duration.ofDays(1))
            "7d" -> Instant.now().minus(Duration.ofDays(7))
            "30d" -> Instant.now().minus(Duration.ofDays(30))
            "all" -> null
            else -> null
        }

        val summary = store.summary(sinceInstant)

        when (format) {
            "text" -> printTextReport(summary)
            "json" -> printJsonReport(summary)
        }
    }

    private fun printTextReport(summary: RefactoringSummary) {
        echo("Martin Refactoring Report")
        echo("=========================")
        echo("Project: $projectDir")
        echo("Period:  ${if (since == "all") "all time" else "last $since"} (${summary.total} refactorings)")
        echo("")

        if (summary.total == 0) {
            echo("No refactorings recorded.")
            return
        }

        echo("By type:")
        val maxTypeLen = summary.byType.maxOf { it.type.length }.coerceAtLeast(4)
        for (ts in summary.byType) {
            val pct = (ts.count.toDouble() / summary.total * 100).toInt()
            val successPct = (ts.successRate * 100).toInt()
            val avgMs = ts.avgDurationMs
            val avgDisplay = if (avgMs < 1000) "${avgMs}ms" else "%.1fs".format(avgMs / 1000.0)
            echo("  %-${maxTypeLen}s  %3d  (%2d%%)  avg %s  %3d%% success".format(
                ts.type, ts.count, pct, avgDisplay, successPct
            ))
        }

        echo("")
        echo("Totals:")
        echo("  ${summary.total} refactorings, ${summary.succeeded} succeeded, ${summary.failed} failed")
        echo("  ${summary.totalFilesModified} files modified, ${summary.totalEdits} edits applied")
        val totalAvg = summary.avgDurationMs
        val totalDisplay = if (totalAvg < 1000) "${totalAvg}ms" else "%.1fs".format(totalAvg / 1000.0)
        echo("  Avg duration: $totalDisplay")
    }

    private fun printJsonReport(summary: RefactoringSummary) {
        val byTypeJson = summary.byType.joinToString(",\n    ") { ts ->
            """{"type":"${ts.type}","count":${ts.count},"successRate":${"%.2f".format(ts.successRate)},"avgDurationMs":${ts.avgDurationMs},"totalFilesModified":${ts.totalFilesModified},"totalEdits":${ts.totalEdits}}"""
        }
        echo("""
{
  "project": "$projectDir",
  "period": "$since",
  "total": ${summary.total},
  "succeeded": ${summary.succeeded},
  "failed": ${summary.failed},
  "totalFilesModified": ${summary.totalFilesModified},
  "totalEdits": ${summary.totalEdits},
  "avgDurationMs": ${summary.avgDurationMs},
  "byType": [
    $byTypeJson
  ]
}
        """.trimIndent())
    }
}
