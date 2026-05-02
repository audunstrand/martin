package martin.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import kotlin.io.path.Path
import martin.mcp.McpServer
import martin.mcp.McpToolRegistry
import martin.mcp.MartinToolRegistrar

/**
 * Starts Martin as an MCP (Model Context Protocol) server over stdio.
 *
 * Usage: martin mcp --project /path/to/project
 *
 * This allows coding agents that speak MCP to use all 25 refactoring
 * tools directly. Communication uses JSON-RPC 2.0 with Content-Length framing.
 */
class McpCommand : CliktCommand(name = "mcp") {

    private val projectDir by option("--project", "-p", help = "Kotlin project directory")
        .path(mustExist = true, canBeFile = false)
        .default(Path("."))

    override fun run() {
        // Redirect logging to stderr so stdout stays clean for MCP protocol
        System.err.println("Martin MCP server starting for project: $projectDir")

        val registry = McpToolRegistry()
        MartinToolRegistrar(projectDir.toAbsolutePath().normalize()).register(registry)

        System.err.println("Registered ${registry.listTools().size} tools")
        System.err.println("Listening on stdio...")

        McpServer(registry).run()
    }
}
