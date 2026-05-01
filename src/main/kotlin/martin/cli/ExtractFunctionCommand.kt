package martin.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.default
import kotlin.io.path.Path
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import martin.daemon.DaemonRequest
import martin.refactoring.ExtractFunctionRefactoring

class ExtractFunctionCommand : CliktCommand(name = "extract-function") {

    private val projectDir by option("--project", "-p").path(mustExist = true, canBeFile = false).default(Path("."))
    private val file by option("--file", "-f").path(mustExist = true, canBeDir = false).required()
    private val startLine by option("--start-line", "-s").int().required()
    private val endLine by option("--end-line", "-e").int().required()
    private val name by option("--name", "-n").required()

    override fun run() = runRefactoring(
        projectDir, "extract-function",
        daemonRequest = DaemonRequest(command = "extract-function", file = file.toString(), startLine = startLine, endLine = endLine, name = name),
    ) { analysis ->
        ExtractFunctionRefactoring(analysis).extract(file, startLine, endLine, name)
    }
}
