package martin.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.OptionWithValues
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import martin.compiler.AnalysisResult
import martin.daemon.DaemonRequest
import martin.refactoring.ParamDef
import martin.refactoring.Refactoring
import martin.refactoring.RefactoringContext
import kotlin.io.path.Path

/**
 * A generic CLI command that wraps any [Refactoring].
 *
 * Standard options (--project, --file, --line, --col) are always registered.
 * Extra parameters from [Refactoring.params] are registered dynamically.
 */
class RefactoringCommand(
    private val factory: (AnalysisResult) -> Refactoring,
    refactoringName: String,
    private val refactoringDescription: String,
    private val paramDefs: List<ParamDef>,
) : CliktCommand(name = refactoringName) {

    private val projectDir by option("--project", "-p", help = "Path to the project root")
        .path(mustExist = true, canBeFile = false).default(Path("."))
    private val file by option("--file", "-f", help = "Path to the Kotlin source file")
        .path(mustExist = true, canBeDir = false)
    private val line by option("--line", "-l", help = "1-based line number").int()
    private val col by option("--col", "-c", help = "1-based column number").int()

    init {
        // Register extra options dynamically — values will be read via registeredOptions() in run()
        for (param in paramDefs) {
            val opt = option("--${param.name}", help = param.description)
            registerOption(opt)
        }
    }

    override fun help(context: Context) = refactoringDescription

    override fun run() {
        // Read extra param values from the dynamically registered options
        val args = mutableMapOf<String, String>()
        for (param in paramDefs) {
            val opt = registeredOptions().first { "--${param.name}" in it.names }
            @Suppress("UNCHECKED_CAST")
            val value = (opt as OptionWithValues<String?, *, *>).value
            if (value != null) {
                args[param.name] = value
            } else if (param.required) {
                throw com.github.ajalt.clikt.core.MissingOption(opt)
            }
        }

        val daemonRequest = DaemonRequest(
            command = commandName,
            file = file?.toString(),
            line = line,
            col = col,
            args = args,
        )

        runRefactoring(projectDir, commandName, daemonRequest) { analysis ->
            val refactoring = factory(analysis)
            val ctx = RefactoringContext(
                analysis = analysis,
                file = file ?: projectDir,
                line = line ?: 0,
                col = col ?: 0,
                args = args,
                projectDir = projectDir,
            )
            refactoring.execute(ctx)
        }
    }
}
