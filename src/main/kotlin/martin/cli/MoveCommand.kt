package martin.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.default
import kotlin.io.path.Path
import com.github.ajalt.clikt.parameters.types.path
import martin.compiler.GradleProjectDiscovery
import martin.refactoring.MoveRefactoring

class MoveCommand : CliktCommand(name = "move") {

    private val projectDir by option("--project", "-p").path(mustExist = true, canBeFile = false).default(Path("."))
    private val symbol by option("--symbol", "-s").required()
    private val toPackage by option("--to-package", "-t").required()

    override fun run() = runRefactoring(projectDir, "move") { analysis ->
        val sourceRoots = GradleProjectDiscovery(projectDir).discoverSourceRoots()
        MoveRefactoring(analysis).move(symbol, toPackage, sourceRoots)
    }
}
