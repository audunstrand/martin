package martin

import martin.refactoring.*
import martin.refactoring.convert.*
import martin.refactoring.core.*
import martin.refactoring.extract.*
import martin.refactoring.restructure.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertTrue

class ConvertToExpressionBodyTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `convert single-return block body to expression body`() {
        val projectDir = createProject(tempDir, mapOf(
            "Foo.kt" to """
                fun double(x: Int): Int {
                    return x * 2
                }
            """.trimIndent()
        ))

        val analysis = analyzeProject(projectDir)
        val file = projectDir.resolve("src/main/kotlin/Foo.kt")
        val edits = ConvertToExpressionBodyRefactoring(analysis).convert(file, line = 1, col = 5)
        val result = applyAndRead(edits, file)

        assertTrue("= x * 2" in result, "Should have expression body. Got:\n$result")
        assertTrue("return" !in result, "Should not have return keyword. Got:\n$result")
    }
}

class ConvertToBlockBodyTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `convert expression body to block body`() {
        val projectDir = createProject(tempDir, mapOf(
            "Foo.kt" to """
                fun double(x: Int): Int = x * 2
            """.trimIndent()
        ))

        val analysis = analyzeProject(projectDir)
        val file = projectDir.resolve("src/main/kotlin/Foo.kt")
        val edits = ConvertToBlockBodyRefactoring(analysis).convert(file, line = 1, col = 5)
        val result = applyAndRead(edits, file)

        assertTrue("return x * 2" in result, "Should have return statement. Got:\n$result")
        assertTrue("{" in result, "Should have block body. Got:\n$result")
    }
}

class ExtractConstantTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `extract literal to constant`() {
        val projectDir = createProject(tempDir, mapOf(
            "Foo.kt" to """
                fun getTimeout(): Int {
                    return 5000
                }
            """.trimIndent()
        ))

        val analysis = analyzeProject(projectDir)
        val file = projectDir.resolve("src/main/kotlin/Foo.kt")
        val edits = ExtractConstantRefactoring(analysis).extract(file, line = 2, col = 12, constantName = "DEFAULT_TIMEOUT")
        val result = applyAndRead(edits, file)

        assertTrue("DEFAULT_TIMEOUT" in result, "Should reference constant. Got:\n$result")
        assertTrue("5000" in result, "Should still contain the value in constant declaration. Got:\n$result")
    }
}

class SafeDeleteTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `safe delete unused function`() {
        val projectDir = createProject(tempDir, mapOf(
            "Foo.kt" to """
                fun used(): Int = 42
                fun unused(): Int = 99
                fun main() { println(used()) }
            """.trimIndent()
        ))

        val analysis = analyzeProject(projectDir)
        val file = projectDir.resolve("src/main/kotlin/Foo.kt")
        val edits = SafeDeleteRefactoring(analysis).delete(file, line = 2, col = 5)
        val result = applyAndRead(edits, file)

        assertTrue("unused" !in result, "Unused function should be deleted. Got:\n$result")
        assertTrue("fun used()" in result, "Used function should remain. Got:\n$result")
    }
}

class ConvertToDataClassTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `convert class with val params to data class`() {
        val projectDir = createProject(tempDir, mapOf(
            "Foo.kt" to """
                class Point(val x: Int, val y: Int)
            """.trimIndent()
        ))

        val analysis = analyzeProject(projectDir)
        val file = projectDir.resolve("src/main/kotlin/Foo.kt")
        val edits = ConvertToDataClassRefactoring(analysis).convert(file, line = 1, col = 7)
        val result = applyAndRead(edits, file)

        assertTrue("data class Point" in result, "Should be data class. Got:\n$result")
    }
}

class ConvertToSealedClassTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `convert abstract class to sealed class`() {
        val projectDir = createProject(tempDir, mapOf(
            "Foo.kt" to """
                abstract class Shape
                class Circle : Shape()
            """.trimIndent()
        ))

        val analysis = analyzeProject(projectDir)
        val file = projectDir.resolve("src/main/kotlin/Foo.kt")
        val edits = ConvertToSealedClassRefactoring(analysis).convert(file, line = 1, col = 16)
        val result = applyAndRead(edits, file)

        assertTrue("sealed class Shape" in result, "Should be sealed class. Got:\n$result")
        assertTrue("abstract" !in result, "Should not have abstract. Got:\n$result")
    }
}

class AddNamedArgumentsTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `add named arguments to function call`() {
        val projectDir = createProject(tempDir, mapOf(
            "Foo.kt" to """
                fun create(name: String, age: Int): String = "${'$'}name:${'$'}age"
                fun main() {
                    val r = create("Alice", 30)
                }
            """.trimIndent()
        ))

        val analysis = analyzeProject(projectDir)
        val file = projectDir.resolve("src/main/kotlin/Foo.kt")
        val edits = AddNamedArgumentsRefactoring(analysis).convert(file, line = 3, col = 13)
        val result = applyAndRead(edits, file)

        assertTrue("name = " in result, "Should have named argument 'name'. Got:\n$result")
        assertTrue("age = " in result, "Should have named argument 'age'. Got:\n$result")
    }
}
