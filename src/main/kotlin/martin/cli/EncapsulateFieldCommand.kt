package martin.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.default
import kotlin.io.path.Path
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import martin.daemon.DaemonRequest
import martin.refactoring.restructure.EncapsulateFieldRefactoring

class EncapsulateFieldCommand : CliktCommand(name = "encapsulate-field") {

    private val projectDir by option("--project", "-p").path(mustExist = true, canBeFile = false).default(Path("."))
    private val file by option("--file", "-f").path(mustExist = true, canBeDir = false).required()
    private val line by option("--line", "-l").int().required()
    private val col by option("--col", "-c").int().required()

    override fun run() = runRefactoring(
        projectDir, "encapsulate-field",
        daemonRequest = DaemonRequest(command = "encapsulate-field", file = file.toString(), line = line, col = col),
    ) { analysis ->
        EncapsulateFieldRefactoring(analysis).encapsulate(file, line, col)
    }
}
