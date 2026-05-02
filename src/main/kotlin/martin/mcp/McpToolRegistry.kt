package martin.mcp

import kotlinx.serialization.json.*

/**
 * Definition of an MCP tool with its name, description, and JSON Schema for input.
 */
data class McpToolDef(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)

/**
 * Registry of MCP tools. Each tool has a handler that receives JSON arguments
 * and returns a text result.
 */
class McpToolRegistry {

    private val tools = mutableListOf<McpToolDef>()
    private val handlers = mutableMapOf<String, (JsonObject) -> String>()

    fun addTool(
        name: String,
        description: String,
        inputSchema: JsonObject,
        handler: (JsonObject) -> String,
    ) {
        tools.add(McpToolDef(name, description, inputSchema))
        handlers[name] = handler
    }

    fun listTools(): List<McpToolDef> = tools.toList()

    fun callTool(name: String, arguments: JsonObject): String {
        val handler = handlers[name] ?: throw IllegalArgumentException("Unknown tool: $name")
        return handler(arguments)
    }
}
