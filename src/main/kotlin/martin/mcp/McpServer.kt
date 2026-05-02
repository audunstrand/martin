package martin.mcp

import kotlinx.serialization.*
import kotlinx.serialization.json.*

/**
 * Lightweight MCP (Model Context Protocol) server implementation over stdio.
 *
 * Implements the JSON-RPC 2.0 based MCP protocol without external dependencies.
 * Uses Content-Length framing (like LSP) for message transport.
 */
class McpServer(
    private val toolRegistry: McpToolRegistry,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    fun run() {
        val input = System.`in`.bufferedReader()
        val output = System.out

        // Redirect stderr for logging so stdout stays clean for MCP
        while (true) {
            val message = readMessage(input) ?: break
            val response = handleMessage(message)
            if (response != null) {
                writeMessage(output, response)
            }
        }
    }

    private fun readMessage(reader: java.io.BufferedReader): String? {
        // Read headers until empty line
        var contentLength = -1
        while (true) {
            val headerLine = reader.readLine() ?: return null
            if (headerLine.isEmpty()) break
            if (headerLine.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = headerLine.substringAfter(":").trim().toInt()
            }
        }
        if (contentLength <= 0) return null

        val buffer = CharArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = reader.read(buffer, read, contentLength - read)
            if (n == -1) return null
            read += n
        }
        return String(buffer)
    }

    private fun writeMessage(output: java.io.OutputStream, message: String) {
        val bytes = message.toByteArray(Charsets.UTF_8)
        val header = "Content-Length: ${bytes.size}\r\n\r\n"
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun handleMessage(raw: String): String? {
        val element = json.parseToJsonElement(raw).jsonObject
        val method = element["method"]?.jsonPrimitive?.contentOrNull
        val id = element["id"]

        return when (method) {
            "initialize" -> handleInitialize(id)
            "initialized" -> null // notification, no response
            "tools/list" -> handleToolsList(id)
            "tools/call" -> handleToolsCall(id, element["params"]?.jsonObject)
            "ping" -> jsonRpcResult(id, buildJsonObject { })
            "notifications/cancelled" -> null
            "shutdown", null -> {
                if (method == null && id != null) {
                    // Unknown request
                    jsonRpcError(id, -32601, "Method not found")
                } else null
            }
            else -> {
                if (id != null) {
                    jsonRpcError(id, -32601, "Method not found: $method")
                } else null // notification we don't handle
            }
        }
    }

    private fun handleInitialize(id: JsonElement?): String {
        val result = buildJsonObject {
            put("protocolVersion", "2024-11-05")
            putJsonObject("capabilities") {
                putJsonObject("tools") {
                    put("listChanged", false)
                }
            }
            putJsonObject("serverInfo") {
                put("name", "martin")
                put("version", "0.1.0")
            }
        }
        return jsonRpcResult(id, result)
    }

    private fun handleToolsList(id: JsonElement?): String {
        val tools = toolRegistry.listTools()
        val toolsArray = buildJsonArray {
            for (tool in tools) {
                add(buildJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("inputSchema", tool.inputSchema)
                })
            }
        }
        val result = buildJsonObject {
            put("tools", toolsArray)
        }
        return jsonRpcResult(id, result)
    }

    private fun handleToolsCall(id: JsonElement?, params: JsonObject?): String {
        val toolName = params?.get("name")?.jsonPrimitive?.contentOrNull
            ?: return jsonRpcError(id, -32602, "Missing tool name")
        val arguments = params["arguments"]?.jsonObject ?: buildJsonObject { }

        return try {
            val result = toolRegistry.callTool(toolName, arguments)
            val response = buildJsonObject {
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", result)
                    })
                })
                put("isError", false)
            }
            jsonRpcResult(id, response)
        } catch (e: Exception) {
            val response = buildJsonObject {
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", e.message ?: "Unknown error")
                    })
                })
                put("isError", true)
            }
            jsonRpcResult(id, response)
        }
    }

    private fun jsonRpcResult(id: JsonElement?, result: JsonObject): String {
        val response = buildJsonObject {
            put("jsonrpc", "2.0")
            if (id != null) put("id", id) else put("id", JsonNull)
            put("result", result)
        }
        return json.encodeToString(JsonObject.serializer(), response)
    }

    private fun jsonRpcError(id: JsonElement?, code: Int, message: String): String {
        val response = buildJsonObject {
            put("jsonrpc", "2.0")
            if (id != null) put("id", id) else put("id", JsonNull)
            putJsonObject("error") {
                put("code", code)
                put("message", message)
            }
        }
        return json.encodeToString(JsonObject.serializer(), response)
    }
}
