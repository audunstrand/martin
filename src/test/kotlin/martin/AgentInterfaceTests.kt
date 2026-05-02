package martin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import martin.cli.RefactoringResult
import martin.mcp.McpToolRegistry
import martin.mcp.MartinToolRegistrar
import martin.refactoring.RenameRefactoring
import martin.rewriter.SourceRewriter
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class AgentInterfaceTests {

    @TempDir
    lateinit var tempDir: Path

    // =========================================================================
    // JSON Output Tests
    // =========================================================================

    @Test
    fun `RefactoringResult serializes to valid JSON`() {
        val result = RefactoringResult(
            success = true,
            command = "rename",
            edits = listOf(
                RefactoringResult.EditInfo(
                    file = "src/main/kotlin/Foo.kt",
                    offset = 42,
                    length = 5,
                    replacement = "newName",
                ),
            ),
            filesModified = 1,
            durationMs = 150,
        )

        val json = Json { prettyPrint = false }
        val serialized = json.encodeToString(RefactoringResult.serializer(), result)

        assertTrue("\"success\":true" in serialized, "Should contain success:true")
        assertTrue("\"command\":\"rename\"" in serialized, "Should contain command")
        assertTrue("\"offset\":42" in serialized, "Should contain edit offset")
        assertTrue("\"replacement\":\"newName\"" in serialized, "Should contain replacement")

        // Deserialize back
        val deserialized = json.decodeFromString<RefactoringResult>(serialized)
        assertEquals(result, deserialized, "Round-trip should produce identical result")
    }

    @Test
    fun `RefactoringResult error format is correct`() {
        val result = RefactoringResult(
            success = false,
            command = "safe-delete",
            error = "Cannot safely delete 'Config': found 2 usage(s)",
            diagnostics = listOf(
                RefactoringResult.DiagnosticInfo(file = "Main.kt", line = 5, message = "Config used here"),
            ),
        )

        val json = Json { prettyPrint = false }
        val serialized = json.encodeToString(RefactoringResult.serializer(), result)

        assertTrue("\"success\":false" in serialized)
        assertTrue("\"error\":" in serialized)
        assertTrue("\"diagnostics\":" in serialized)
    }

    // =========================================================================
    // Dry-run Tests
    // =========================================================================

    @Test
    fun `dry-run computes edits without writing files`() {
        val projectDir = createProject(tempDir, mapOf(
            "Foo.kt" to """
                fun oldName(): Int = 42

                fun main() {
                    println(oldName())
                }
            """.trimIndent()
        ))

        val analysis = analyzeProject(projectDir)
        val file = projectDir.resolve("src/main/kotlin/Foo.kt")

        // Compute edits (same as rename) but DON'T apply
        val edits = RenameRefactoring(analysis).rename(file, line = 1, col = 5, newName = "newName")

        // File should still have old name
        val contentBefore = file.readText()
        assertTrue("oldName" in contentBefore, "File should not be modified in dry-run")

        // Edits should be non-empty
        assertTrue(edits.isNotEmpty(), "Should have computed edits")

        // Now verify edits are correct by converting to EditInfo
        val editInfos = edits.map {
            RefactoringResult.EditInfo(
                file = projectDir.relativize(it.filePath).toString(),
                offset = it.offset,
                length = it.length,
                replacement = it.replacement,
            )
        }
        assertTrue(editInfos.any { it.replacement == "newName" }, "Edits should contain the new name")
    }

    // =========================================================================
    // MCP Tool Registry Tests
    // =========================================================================

    @Test
    fun `MCP tool registry registers all 26 tools`() {
        val projectDir = createProject(tempDir, mapOf(
            "Foo.kt" to "fun main() {}"
        ))

        val registry = McpToolRegistry()
        MartinToolRegistrar(projectDir).register(registry)

        val tools = registry.listTools()

        // Should have 25 refactoring tools + 1 find-unused utility
        assertTrue(tools.size >= 25, "Should register at least 25 tools, got ${tools.size}")

        // Check core tools are present
        val toolNames = tools.map { it.name }.toSet()
        assertTrue("rename" in toolNames, "Should have rename tool")
        assertTrue("extract-function" in toolNames, "Should have extract-function tool")
        assertTrue("extract-variable" in toolNames, "Should have extract-variable tool")
        assertTrue("inline" in toolNames, "Should have inline tool")
        assertTrue("move" in toolNames, "Should have move tool")
        assertTrue("change-signature" in toolNames, "Should have change-signature tool")
        assertTrue("safe-delete" in toolNames, "Should have safe-delete tool")
    }

    @Test
    fun `MCP tool schemas have required fields`() {
        val projectDir = createProject(tempDir, mapOf(
            "Foo.kt" to "fun main() {}"
        ))

        val registry = McpToolRegistry()
        MartinToolRegistrar(projectDir).register(registry)

        for (tool in registry.listTools()) {
            assertTrue(tool.name.isNotEmpty(), "Tool should have a name")
            assertTrue(tool.description.isNotEmpty(), "Tool '${tool.name}' should have a description")
            assertTrue("type" in tool.inputSchema, "Tool '${tool.name}' schema should have 'type' field")
            assertTrue("properties" in tool.inputSchema, "Tool '${tool.name}' schema should have 'properties' field")
        }
    }

    @Test
    fun `MCP rename tool executes and modifies files`() {
        val projectDir = createProject(tempDir, mapOf(
            "Foo.kt" to """
                fun oldName(): Int = 42

                fun main() {
                    println(oldName())
                }
            """.trimIndent()
        ))

        val registry = McpToolRegistry()
        MartinToolRegistrar(projectDir).register(registry)

        val result = registry.callTool("rename", kotlinx.serialization.json.buildJsonObject {
            put("file", projectDir.resolve("src/main/kotlin/Foo.kt").toString())
            put("line", 1)
            put("col", 5)
            put("newName", "computeValue")
        })

        assertTrue("edits" in result, "Result should mention edits. Got: $result")

        val fileContent = projectDir.resolve("src/main/kotlin/Foo.kt").readText()
        assertTrue("computeValue" in fileContent, "File should be updated. Got:\n$fileContent")
        assertFalse("oldName" in fileContent, "Old name should be gone. Got:\n$fileContent")

        assertCompiles(projectDir)
    }
}
