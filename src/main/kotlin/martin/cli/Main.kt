package martin.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option

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
    Martin()
        .subcommands(
            // Phase 1: Core refactorings
            RenameCommand(),
            ExtractFunctionCommand(),
            ExtractVariableCommand(),
            InlineCommand(),
            MoveCommand(),
            ChangeSignatureCommand(),
            // Batch 1: Expression/body conversions
            ConvertToExpressionBodyCommand(),
            ConvertToBlockBodyCommand(),
            AddNamedArgumentsCommand(),
            // Batch 2: Extraction and deletion
            ExtractConstantCommand(),
            SafeDeleteCommand(),
            ConvertPropertyToFunctionCommand(),
            // Batch 3: Parameter refactorings
            ExtractParameterCommand(),
            IntroduceParameterObjectCommand(),
            // Batch 4: Class hierarchy
            ExtractInterfaceCommand(),
            ExtractSuperclassCommand(),
            PullUpMethodCommand(),
            ReplaceConstructorWithFactoryCommand(),
            // Batch 5: Kotlin idioms
            ConvertToDataClassCommand(),
            ConvertToExtensionFunctionCommand(),
            ConvertToSealedClassCommand(),
            // Batch 6: Advanced
            EncapsulateFieldCommand(),
            TypeMigrationCommand(),
            MoveStatementsIntoFunctionCommand(),
            // Metrics
            ReportCommand(),
            // Setup
            InitCommand(),
            // Easter egg
            FowlerCommand(),
            // Daemon
            DaemonCommand(),
            // MCP server
            McpCommand(),
        )
        .main(args)
}
