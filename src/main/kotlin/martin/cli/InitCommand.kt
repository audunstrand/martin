package martin.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.default
import java.io.File

class InitCommand : CliktCommand(name = "init") {

    private val project by option("-p", "--project").default(".")

    override fun run() {
        val skillsContent = this::class.java.getResourceAsStream("/SKILLS.md")
            ?.bufferedReader()?.readText()
            ?: error("SKILLS.md resource not found in JAR")

        val target = File(project, "SKILLS.md")
        target.writeText(skillsContent)
        echo("Wrote ${target.absolutePath}")
    }
}
