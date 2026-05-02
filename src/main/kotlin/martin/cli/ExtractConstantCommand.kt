package martin.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.default
import kotlin.io.path.Path
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import martin.daemon.DaemonRequest
import martin.refactoring.extract.ExtractConstantRefactoring

class ExtractConstantCommand : CliktCommand(name = "extract-constant") {

    private val projectDir by option("--project", "-p").path(mustExist = true, canBeFile = false).default(Path("."))
    private val file by option("--file", "-f").path(mustExist = true, canBeDir = false).required()
    private val line by option("--line", "-l").int().required()
    private val col by option("--col", "-c").int().required()
    private val constantName by option("--name", "-n").required()

    override fun run() = runRefactoring(
        projectDir, "extract-constant",
        daemonRequest = DaemonRequest(command = "extract-constant", file = file.toString(), line = line, col = col, name = constantName),
    ) { analysis ->
        ExtractConstantRefactoring(analysis).extract(file, line, col, constantName)
    }
}
