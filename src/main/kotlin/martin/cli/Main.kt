xpackage martin.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import martin.refactoring.RefactoringRegistry

/**
 * Global configuration passed from the root Martin command to subcommands.
 */
data class MartinConfig(
    var format: String = "text",
    var dryRun: Boolean = false,
)

/** Singleton config — set by the root command, read by subcommands. */
val globalConfig = MartinConfig()

class Martin : CliktCommand() {

    private val format by option("--format", help = "Output format: text or json").default("text")
    private val dryRun by option("--dry-run", help = "Compute edits without writing to disk").flag()

    override fun help(context: Context) = "Kotlin refactoring CLI for coding agents"

    override fun run() {
        globalConfig.format = format
        globalConfig.dryRun = dryRun
    }
}

fun main(args: Array<String>) {
    val refactoringCommands = RefactoringRegistry.entries.map { entry ->
        RefactoringCommand(
            factory = entry.factory,
            refactoringName = entry.name,
            refactoringDescription = entry.description,
            paramDefs = entry.params,
        )
    }

    Martin()
        .subcommands(
            refactoringCommands + listOf(
                ReportCommand(),
                InitCommand(),
                FowlerCommand(),
                DaemonCommand(),
                McpCommand(),
            )
        )
        .main(args)
}
