package martin.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.default
import kotlin.io.path.Path
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import martin.daemon.DaemonRequest
import martin.refactoring.IntroduceParameterObjectRefactoring

class IntroduceParameterObjectCommand : CliktCommand(name = "introduce-parameter-object") {

    private val projectDir by option("--project", "-p").path(mustExist = true, canBeFile = false).default(Path("."))
    private val file by option("--file", "-f").path(mustExist = true, canBeDir = false).required()
    private val line by option("--line", "-l").int().required()
    private val col by option("--col", "-c").int().required()
    private val className by option("--class-name").required()
    private val paramNames by option("--params").split(",").required()

    override fun run() = runRefactoring(
        projectDir, "introduce-parameter-object",
        daemonRequest = DaemonRequest(command = "introduce-parameter-object", file = file.toString(), line = line, col = col, name = className, paramNames = paramNames.joinToString(",")),
    ) { analysis ->
        IntroduceParameterObjectRefactoring(analysis).introduce(file, line, col, className, paramNames)
    }
}
