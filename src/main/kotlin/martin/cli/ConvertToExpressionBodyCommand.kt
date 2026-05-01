package martin.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.default
import kotlin.io.path.Path
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import martin.daemon.DaemonRequest
import martin.refactoring.ConvertToExpressionBodyRefactoring

class ConvertToExpressionBodyCommand : CliktCommand(name = "convert-to-expression-body") {

    private val projectDir by option("--project", "-p").path(mustExist = true, canBeFile = false).default(Path("."))
    private val file by option("--file", "-f").path(mustExist = true, canBeDir = false).required()
    private val line by option("--line", "-l").int().required()
    private val col by option("--col", "-c").int().required()

    override fun run() = runRefactoring(
        projectDir, "convert-to-expression-body",
        daemonRequest = DaemonRequest(command = "convert-to-expression-body", file = file.toString(), line = line, col = col),
    ) { analysis ->
        ConvertToExpressionBodyRefactoring(analysis).convert(file, line, col)
    }
}
