package martin.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.default
import kotlin.io.path.Path
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import martin.daemon.DaemonRequest
import martin.refactoring.convert.ConvertToSealedClassRefactoring

class ConvertToSealedClassCommand : CliktCommand(name = "convert-to-sealed-class") {

    private val projectDir by option("--project", "-p").path(mustExist = true, canBeFile = false).default(Path("."))
    private val file by option("--file", "-f").path(mustExist = true, canBeDir = false).required()
    private val line by option("--line", "-l").int().required()
    private val col by option("--col", "-c").int().required()

    override fun run() = runRefactoring(
        projectDir, "convert-to-sealed-class",
        daemonRequest = DaemonRequest(command = "convert-to-sealed-class", file = file.toString(), line = line, col = col),
    ) { analysis ->
        ConvertToSealedClassRefactoring(analysis).convert(file, line, col)
    }
}
