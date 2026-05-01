package martin.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.default
import kotlin.io.path.Path
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import martin.daemon.DaemonRequest
import martin.refactoring.ChangeSignatureRefactoring
import martin.refactoring.ChangeSignatureRefactoring.ParameterSpec

class ChangeSignatureCommand : CliktCommand(name = "change-signature") {

    private val projectDir by option("--project", "-p").path(mustExist = true, canBeFile = false).default(Path("."))
    private val file by option("--file", "-f").path(mustExist = true, canBeDir = false).required()
    private val line by option("--line", "-l").int().required()
    private val col by option("--col", "-c").int().required()
    private val params by option("--params").required()

    override fun run() = runRefactoring(
        projectDir, "change-signature",
        daemonRequest = DaemonRequest(command = "change-signature", file = file.toString(), line = line, col = col, params = params),
    ) { analysis ->
        ChangeSignatureRefactoring(analysis).changeSignature(file, line, col, parseParameterSpecs(params))
    }
}

private fun parseParameterSpecs(raw: String): List<ParameterSpec> =
    raw.split(",").map { param ->
        val parts = param.split(":")
        val name = parts[0].trim()
        val typeAndDefault = parts.getOrNull(1)?.split("=") ?: error("Missing type for param '$name'")
        val type = typeAndDefault[0].trim()
        val default = typeAndDefault.getOrNull(1)?.trim()?.let { "\"$it\"" }
        ParameterSpec(name, type, default)
    }
