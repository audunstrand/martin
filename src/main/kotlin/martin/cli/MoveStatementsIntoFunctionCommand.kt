package martin.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.default
import kotlin.io.path.Path
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import martin.daemon.DaemonRequest
import martin.refactoring.restructure.MoveStatementsIntoFunctionRefactoring

class MoveStatementsIntoFunctionCommand : CliktCommand(name = "move-statements-into-function") {

    private val projectDir by option("--project", "-p").path(mustExist = true, canBeFile = false).default(Path("."))
    private val file by option("--file", "-f").path(mustExist = true, canBeDir = false).required()
    private val functionLine by option("--function-line").int().required()
    private val functionCol by option("--function-col").int().required()
    private val startLine by option("--start-line").int().required()
    private val endLine by option("--end-line").int().required()

    override fun run() = runRefactoring(
        projectDir, "move-statements-into-function",
        daemonRequest = DaemonRequest(command = "move-statements-into-function", file = file.toString(), functionLine = functionLine, functionCol = functionCol, startLine = startLine, endLine = endLine),
    ) { analysis ->
        MoveStatementsIntoFunctionRefactoring(analysis).move(file, functionLine, functionCol, startLine, endLine)
    }
}
