package martin

import martin.refactoring.*
import martin.refactoring.InlineRefactoring.SourceLocation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.assertTrue

class RenameTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `rename a function updates declaration and all call sites`() {
        val projectDir = createProject(tempDir, mapOf(
            "Foo.kt" to """
                fun greet(name: String): String {
                    return "Hello, ${'$'}name"
                }

                fun main() {
                    val result = greet("World")
                    println(result)
                }
            """.trimIndent()
        ))

        val analysis = analyzeProject(projectDir)
        val file = projectDir.resolve("src/main/kotlin/Foo.kt")
        val edits = RenameRefactoring(analysis).rename(file, line = 1, col = 5, newName = "sayHello")
        val result = applyAndRead(edits, file)

        assertTrue("fun sayHello(" in result)
        assertTrue("sayHello(\"World\")" in result)
        assertTrue("greet" !in result)
    }

    @Test
    fun `rename a variable updates declaration and usages`() {
        val projectDir = createProject(tempDir, mapOf(
            "Bar.kt" to """
                fun compute() {
                    val count = 10
                    val doubled = count * 2
                    println(count)
                }
            """.trimIndent()
        ))

        val analysis = analyzeProject(projectDir)
        val file = projectDir.resolve("src/main/kotlin/Bar.kt")
        val edits = RenameRefactoring(analysis).rename(file, line = 2, col = 9, newName = "total")
        val result = applyAndRead(edits, file)

        assertTrue("val total = 10" in result)
        assertTrue("total * 2" in result)
        assertTrue("println(total)" in result)
    }

    @Test
    fun `rename across multiple files`() {
        val projectDir = createProject(tempDir, mapOf(
            "Utils.kt" to """
                fun helper(): Int {
                    return 42
                }
            """.trimIndent(),
            "Main.kt" to """
                fun main() {
                    val x = helper()
                    println(x)
                }
            """.trimIndent()
        ))

        val analysis = analyzeProject(projectDir)
        val file = projectDir.resolve("src/main/kotlin/Utils.kt")
        val edits = RenameRefactoring(analysis).rename(file, line = 1, col = 5, newName = "utility")

        assertTrue(edits.size >= 2)
        val result = applyAndRead(edits, file)

        assertTrue("fun utility()" in result)
        assertTrue("utility()" in projectDir.resolve("src/main/kotlin/Main.kt").readText())
    }
}

class ExtractFunctionTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `extract function from simple lines`() {
        val projectDir = createProject(tempDir, mapOf(
            "Foo.kt" to """
                fun process() {
                    val x = 1
                    val y = 2
                    println(x + y)
                    val z = 10
                }
            """.trimIndent()
        ))

        val analysis = analyzeProject(projectDir)
        val file = projectDir.resolve("src/main/kotlin/Foo.kt")
        val edits = ExtractFunctionRefactoring(analysis).extract(file, startLine = 3, endLine = 4, functionName = "printSum")
        val result = applyAndRead(edits, file)

        assertTrue("printSum(" in result, "Should contain call to extracted function. Got:\n$result")
        assertTrue("fun printSum(" in result, "Should contain extracted function definition. Got:\n$result")
    }

    @Test
    fun `extract function with inner lambda parameters should not leak them as params`() {
        val projectDir = createProject(tempDir, mapOf(
            "Foo.kt" to """
                fun process(items: List<String>) {
                    val prefix = "hello"
                    items.forEach { item ->
                        println(prefix + item)
                    }
                }
            """.trimIndent()
        ))

        val analysis = analyzeProject(projectDir)
        val file = projectDir.resolve("src/main/kotlin/Foo.kt")
        // Extract the forEach block (lines 3-5), which contains lambda param `item`
        val edits = ExtractFunctionRefactoring(analysis).extract(file, startLine = 3, endLine = 5, functionName = "printItems")
        val result = applyAndRead(edits, file)

        assertTrue("fun printItems(" in result, "Should contain extracted function. Got:\n$result")
        // `item` is a lambda parameter inside the extracted range — it should NOT be a function parameter
        assertTrue("item:" !in result.replace("\\s".toRegex(), "").let { result },
            "Lambda param 'item' should not become a function parameter. Got:\n$result")
    }

    @Test
    fun `extracted function from nested scope has correct indentation`() {
        val projectDir = createProject(tempDir, mapOf(
            "Foo.kt" to """
                class Processor {
                    fun process() {
                        val x = 1
                        println(x)
                    }
                }
            """.trimIndent()
        ))

        val analysis = analyzeProject(projectDir)
        val file = projectDir.resolve("src/main/kotlin/Foo.kt")
        // Extract println(x) at line 4 (8-space indent inside class+fun)
        val edits = ExtractFunctionRefactoring(analysis).extract(file, startLine = 4, endLine = 4, functionName = "printX")
        val result = applyAndRead(edits, file)

        // The extracted function should be at class member level (4-space indent), not 8-space
        assertTrue("    private fun printX(" in result,
            "Extracted function should be at class member indent level. Got:\n$result")
        // Body should be indented one level deeper than the function declaration
        assertTrue("        println(x)" in result,
            "Function body should be indented relative to function declaration. Got:\n$result")
    }

    @Test
    fun `extracted function gets suspend modifier when calling suspend functions`() {
        val projectDir = createProject(tempDir, mapOf(
            "Foo.kt" to """
                suspend fun doWork(): String = "done"

                suspend fun process() {
                    val result = doWork()
                    println(result)
                }
            """.trimIndent()
        ))

        val analysis = analyzeProject(projectDir)
        val file = projectDir.resolve("src/main/kotlin/Foo.kt")
        // Extract the suspend call (line 4)
        val edits = ExtractFunctionRefactoring(analysis).extract(file, startLine = 4, endLine = 4, functionName = "fetchResult")
        val result = applyAndRead(edits, file)

        assertTrue("suspend fun fetchResult(" in result,
            "Extracted function should have suspend modifier. Got:\n$result")
    }
}

class ExtractVariableTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `extract call expression into variable`() {
        val projectDir = createProject(tempDir, mapOf(
            "Foo.kt" to """
                fun process() {
                    println(listOf(1, 2, 3).size)
                }
            """.trimIndent()
        ))

        val analysis = analyzeProject(projectDir)
        val file = projectDir.resolve("src/main/kotlin/Foo.kt")
        val edits = ExtractVariableRefactoring(analysis).extract(file, line = 2, col = 13, variableName = "numbers")
        val result = applyAndRead(edits, file)

        assertTrue("val numbers" in result, "Should contain variable declaration. Got:\n$result")
    }

    @Test
    fun `extract RHS of existing val into new variable`() {
        val projectDir = createProject(tempDir, mapOf(
            "Foo.kt" to """
                fun process() {
                    val logFormat = "COMMIT_START"
                }
            """.trimIndent()
        ))

        val analysis = analyzeProject(projectDir)
        val file = projectDir.resolve("src/main/kotlin/Foo.kt")
        // cursor on the string literal (RHS of val logFormat)
        val edits = ExtractVariableRefactoring(analysis).extract(file, line = 2, col = 21, variableName = "gitLogFormat")
        val result = applyAndRead(edits, file)

        assertTrue("val gitLogFormat" in result, "Should contain new variable declaration. Got:\n$result")
        assertTrue("val logFormat = gitLogFormat" in result, "Original val should reference new variable. Got:\n$result")
        // Ensure no garbled output (val on same line as another val)
        assertTrue("val         val" !in result && "val     val" !in result, "Should not have garbled val declarations. Got:\n$result")
    }
}

class InlineTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `inline a variable`() {
        val projectDir = createProject(tempDir, mapOf(
            "Foo.kt" to """
                fun process() {
                    val message = "hello"
                    println(message)
                }
            """.trimIndent()
        ))

        val analysis = analyzeProject(projectDir)
        val file = projectDir.resolve("src/main/kotlin/Foo.kt")
        val edits = InlineRefactoring(analysis).inline(SourceLocation(file, 2, 9))
        val result = applyAndRead(edits, file)

        assertTrue("val message" !in result, "Variable declaration should be removed. Got:\n$result")
        assertTrue("\"hello\"" in result, "Initializer should replace usage. Got:\n$result")
    }
}

class ChangeSignatureTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `add a parameter to a function`() {
        val projectDir = createProject(tempDir, mapOf(
            "Foo.kt" to """
                fun greet(name: String): String {
                    return "Hello, ${'$'}name"
                }

                fun main() {
                    println(greet("World"))
                }
            """.trimIndent()
        ))

        val analysis = analyzeProject(projectDir)
        val file = projectDir.resolve("src/main/kotlin/Foo.kt")
        val edits = ChangeSignatureRefactoring(analysis).changeSignature(
            file, line = 1, col = 5,
            newParams = listOf(
                ChangeSignatureRefactoring.ParameterSpec("name", "String"),
                ChangeSignatureRefactoring.ParameterSpec("greeting", "String", "\"Hello\""),
            )
        )
        val result = applyAndRead(edits, file)

        assertTrue("name: String, greeting: String = \"Hello\"" in result, "Declaration should have new param. Got:\n$result")
    }

    @Test
    fun `remove a parameter from a function`() {
        val projectDir = createProject(tempDir, mapOf(
            "Foo.kt" to """
                fun add(a: Int, b: Int): Int {
                    return a + b
                }

                fun main() {
                    println(add(1, 2))
                }
            """.trimIndent()
        ))

        val analysis = analyzeProject(projectDir)
        val file = projectDir.resolve("src/main/kotlin/Foo.kt")
        val edits = ChangeSignatureRefactoring(analysis).changeSignature(
            file, line = 1, col = 5,
            newParams = listOf(
                ChangeSignatureRefactoring.ParameterSpec("a", "Int"),
            )
        )
        val result = applyAndRead(edits, file)

        assertTrue("fun add(a: Int)" in result, "Declaration should only have 'a'. Got:\n$result")
        assertTrue("add(1)" in result, "Call site should only have first arg. Got:\n$result")
    }
}
