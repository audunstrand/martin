package martin.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import kotlin.io.path.Path
import martin.daemon.DaemonClient
import martin.daemon.DaemonRequest
import martin.daemon.MartinDaemon

class DaemonCommand : CliktCommand(name = "daemon") {
    override fun run() = Unit

    init {
        subcommands(DaemonStartCommand(), DaemonStopCommand(), DaemonStatusCommand())
    }
}

class DaemonStartCommand : CliktCommand(name = "start") {
    private val projectDir by option("--project", "-p").path(mustExist = true, canBeFile = false).default(Path("."))

    override fun run() {
        MartinDaemon(projectDir).start()
    }
}

class DaemonStopCommand : CliktCommand(name = "stop") {
    private val projectDir by option("--project", "-p").path(mustExist = true, canBeFile = false).default(Path("."))

    override fun run() {
        val response = DaemonClient.send(projectDir, DaemonRequest(command = "stop"))
        if (response != null) {
            echo("Daemon stopped")
        } else {
            echo("No daemon running")
        }
    }
}

class DaemonStatusCommand : CliktCommand(name = "status") {
    private val projectDir by option("--project", "-p").path(mustExist = true, canBeFile = false).default(Path("."))

    override fun run() {
        val response = DaemonClient.send(projectDir, DaemonRequest(command = "status"))
        if (response != null) {
            echo("Daemon: ${response.message}")
        } else {
            echo("No daemon running")
        }
    }
}
