package martin.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands

class Martin : CliktCommand() {
    override fun run() = Unit
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
        )
        .main(args)
}
