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
import martin.refactoring.extract.ExtractSuperclassRefactoring

class ExtractSuperclassCommand : CliktCommand(name = "extract-superclass") {

    private val projectDir by option("--project", "-p").path(mustExist = true, canBeFile = false).default(Path("."))
    private val file by option("--file", "-f").path(mustExist = true, canBeDir = false).required()
    private val line by option("--line", "-l").int().required()
    private val col by option("--col", "-c").int().required()
    private val superclassName by option("--superclass-name").required()
    private val members by option("--members").split(",").required()

    override fun run() = runRefactoring(
        projectDir, "extract-superclass",
        daemonRequest = DaemonRequest(command = "extract-superclass", file = file.toString(), line = line, col = col, name = superclassName, members = members.joinToString(",")),
    ) { analysis ->
        val output = ExtractSuperclassRefactoring(analysis).extract(file, line, col, superclassName, members)
        output.writeNewFiles()
        output.edits
    }
}
