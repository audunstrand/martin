package martin

import martin.refactoring.*
import martin.refactoring.convert.*
import martin.refactoring.core.*
import martin.refactoring.core.InlineRefactoring.SourceLocation
import martin.refactoring.extract.*
import martin.refactoring.restructure.*
import martin.rewriter.SourceRewriter
import martin.compiler.GradleProjectDiscovery
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.assertTrue
import kotlin.test.assertFalse

// =============================================================================
// Rename End-to-End Tests
// =============================================================================

class RenameEndToEndTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `rename class updates type refs, constructor calls, and imports across files`() {
        val projectDir = createProject(tempDir, mapOf(
            "pkg/Model.kt" to """
                package pkg

                class UserProfile(val name: String, val age: Int)
            """.trimIndent(),
            "pkg/Service.kt" to """
                package pkg

                class Service {
                    fun create(): UserProfile {
                        return UserProfile("Alice", 30)
                    }

                    fun process(profile: UserProfile): String {
                        return profile.name
                    }
                }
            """.trimIndent()
        ))

        val analysis = analyzeProject(projectDir)
        val file = projectDir.resolve("src/main/kotlin/pkg/Model.kt")
        val edits = RenameRefactoring(analysis).rename(file, line = 3, col = 7, newName = "AccountProfile")

        SourceRewriter.applyEdits(edits)

        val modelResult = file.readText()
        val serviceResult = projectDir.resolve("src/main/kotlin/pkg/Service.kt").readText()

        assertTrue("class AccountProfile" in modelResult, "Class declaration should be renamed. Got:\n$modelResult")
        assertTrue("AccountProfile(" in serviceResult, "Constructor call should be renamed. Got:\n$serviceResult")
        assertTrue("profile: AccountProfile" in serviceResult, "Type reference should be renamed. Got:\n$serviceResult")
        assertTrue(": AccountProfile" in serviceResult, "Return type should be renamed. Got:\n$serviceResult")
        assertFalse("UserProfile" in modelResult, "Old name should not remain in Model.kt. Got:\n$modelResult")
        assertFalse("UserProfile" in serviceResult, "Old name should not remain in Service.kt. Got:\n$serviceResult")

        assertCompiles(projectDir)
    }

    @Test
    fun `rename function updates cross-file import and call sites`() {
        val projectDir = createProject(tempDir, mapOf(
            "pkg/Utils.kt" to """
                package pkg

                fun calculateTotal(items: List<Int>): Int {
                    return items.sum()
                }
            """.trimIndent(),
            "pkg/Main.kt" to """
                package pkg

                fun main() {
                    val total = calculateTotal(listOf(1, 2, 3))
                    println(total)
                }
            """.trimIndent()
        ))

        val analysis = analyzeProject(projectDir)
        val file = projectDir.resolve("src/main/kotlin/pkg/Utils.kt")
        val edits = RenameRefactoring(analysis).rename(file, line = 3, col = 5, newName = "computeSum")

        SourceRewriter.applyEdits(edits)

        val utilsResult = file.readText()
        val mainResult = projectDir.resolve("src/main/kotlin/pkg/Main.kt").readText()

        assertTrue("fun computeSum(" in utilsResult, "Declaration should be renamed. Got:\n$utilsResult")
        assertTrue("computeSum(" in mainResult, "Call site should be renamed. Got:\n$mainResult")
        assertFalse("calculateTotal" in utilsResult, "Old name should not remain. Got:\n$utilsResult")
        assertFalse("calculateTotal" in mainResult, "Old name should not remain in caller. Got:\n$mainResult")

        assertCompiles(projectDir)
    }

    @Test
    fun `rename parameter used in string template`() {
        val projectDir = createProject(tempDir, mapOf(
            "Foo.kt" to """
                fun greet(name: String): String {
                    return "Hello, ${'$'}name! Welcome."
                }

                fun main() {
                    println(greet("Alice"))
                }
            """.trimIndent()
        ))

        val analysis = analyzeProject(projectDir)
        val file = projectDir.resolve("src/main/kotlin/Foo.kt")
        // cursor on `name` in parameter declaration
        val edits = RenameRefactoring(analysis).rename(file, line = 1, col = 11, newName = "person")

        val result = applyAndRead(edits, file)

        assertTrue("person: String" in result, "Parameter should be renamed. Got:\n$result")
        assertTrue("\$person" in result, "String template reference should be updated. Got:\n$result")
        assertFalse("\$name" in result, "Old template reference should not remain. Got:\n$result")

        assertCompiles(projectDir)
    }

    @Test
    fun `rename method inside nested class`() {
        val projectDir = createProject(tempDir, mapOf(
            "Foo.kt" to """
                class Outer {
                    class Inner {
                        fun doWork(): String = "done"
                    }

                    fun run(): String {
                        return Inner().doWork()
                    }
                }

                fun main() {
                    val result = Outer.Inner().doWork()
                    println(result)
                }
            """.trimIndent()
        ))

        val analysis = analyzeProject(projectDir)
        val file = projectDir.resolve("src/main/kotlin/Foo.kt")
        // cursor on doWork in the declaration inside Inner
        val edits = RenameRefactoring(analysis).rename(file, line = 3, col = 13, newName = "execute")

        val result = applyAndRead(edits, file)

        assertTrue("fun execute()" in result, "Declaration should be renamed. Got:\n$result")
        assertTrue("Inner().execute()" in result, "Call in Outer.run() should be renamed. Got:\n$result")
        assertTrue("Outer.Inner().execute()" in result, "Call in main() should be renamed. Got:\n$result")
        assertFalse("doWork" in result, "Old name should not remain. Got:\n$result")

        assertCompiles(projectDir)
    }
}

// =============================================================================
// ExtractFunction End-to-End Tests
// =============================================================================

class ExtractFunctionEndToEndTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `extract from nested lambdas with captured outer vars only`() {
        val projectDir = createProject(tempDir, mapOf(
            "Foo.kt" to """
                fun process(items: List<String>, prefix: String) {
                    val results = mutableListOf<String>()
                    items.filter { it.isNotEmpty() }
                        .map { item ->
                            prefix + item.uppercase()
                        }
                        .forEach { transformed ->
                            results.add(transformed)
                        }
                    println(results)
                }
            """.trimIndent()
        ))

        val analysis = analyzeProject(projectDir)
        val file = projectDir.resolve("src/main/kotlin/Foo.kt")
        // Extract lines 3-9 (the filter/map/forEach chain)
        val edits = ExtractFunctionRefactoring(analysis).extract(file, startLine = 3, endLine = 9, functionName = "transformItems")

        val result = applyAndRead(edits, file)

        assertTrue("fun transformItems(" in result, "Should contain extracted function. Got:\n$result")
        assertTrue("transformItems(" in result, "Should contain call to extracted function. Got:\n$result")
        // inner lambda params (item, transformed, it) should NOT be function parameters
        assertFalse("item:" in result.replace("\\s".toRegex(), ""),
            "Lambda param 'item' should not be a function parameter. Got:\n$result")
        assertFalse("transformed:" in result.replace("\\s".toRegex(), ""),
            "Lambda param 'transformed' should not be a function parameter. Got:\n$result")
    }

    @Test
    fun `extract from init block`() {
        val projectDir = createProject(tempDir, mapOf(
            "Foo.kt" to """
                class Config {
                    val items = mutableListOf<String>()

                    init {
                        val defaults = listOf("a", "b", "c")
                        for (d in defaults) {
                            items.add(d.uppercase())
                        }
                    }
                }
            """.trimIndent()
        ))

        val analysis = analyzeProject(projectDir)
        val file = projectDir.resolve("src/main/kotlin/Foo.kt")
        val edits = ExtractFunctionRefactoring(analysis).extract(file, startLine = 6, endLine = 8, functionName = "addDefaults")

        val result = applyAndRead(edits, file)

        assertTrue("fun addDefaults(" in result, "Should contain extracted function. Got:\n$result")
        assertCompiles(projectDir)
    }

    @Test
    fun `extract code producing a return value used after extraction`() {
        val projectDir = createProject(tempDir, mapOf(
            "Foo.kt" to """
                fun process(input: String): String {
                    val trimmed = input.trim()
                    val upper = trimmed.uppercase()
                    val result = upper + "!"
                    return result
                }
            """.trimIndent()
        ))

        val analysis = analyzeProject(projectDir)
        val file = projectDir.resolve("src/main/kotlin/Foo.kt")
        // Extract lines 2-3 (trimmed and upper), where `upper` is used after on line 4
        val edits = ExtractFunctionRefactoring(analysis).extract(file, startLine = 2, endLine = 3, functionName = "normalize")

        val result = applyAndRead(edits, file)

        assertTrue("fun normalize(" in result, "Should contain extracted function. Got:\n$result")
        assertTrue("normalize(" in result, "Should contain call to extracted function. Got:\n$result")
    }

    @Test
    fun `extract multi-line with varying sub-indentation`() {
        val projectDir = createProject(tempDir, mapOf(
            "Foo.kt" to """
                class Processor {
                    fun process(items: List<Int>): List<String> {
                        val results = mutableListOf<String>()
                        for (item in items) {
                            if (item > 0) {
                                val label = "positive: ${'$'}item"
                                results.add(label)
                            } else {
                                results.add("non-positive")
                            }
                        }
                        return results
                    }
                }
            """.trimIndent()
        ))

        val analysis = analyzeProject(projectDir)
        val file = projectDir.resolve("src/main/kotlin/Foo.kt")
        // Extract the for-loop body (lines 4-11)
        val edits = ExtractFunctionRefactoring(analysis).extract(file, startLine = 4, endLine = 11, functionName = "categorize")

        val result = applyAndRead(edits, file)

        assertTrue("fun categorize(" in result, "Should contain extracted function. Got:\n$result")
        // Check that the function declaration is at class member level (4 spaces), not deeper
        assertTrue("\n    private fun categorize(" in result || result.startsWith("    private fun categorize("),
            "Extracted function should be at class member indent. Got:\n$result")
    }

    @Test
    fun `extract code that uses this in class member context`() {
        val projectDir = createProject(tempDir, mapOf(
            "Foo.kt" to """
                class Counter {
                    var count = 0

                    fun increment(amount: Int) {
                        this.count += amount
                        println("Count is now ${'$'}{this.count}")
                    }
                }
            """.trimIndent()
        ))

        val analysis = analyzeProject(projectDir)
        val file = projectDir.resolve("src/main/kotlin/Foo.kt")
        val edits = ExtractFunctionRefactoring(analysis).extract(file, startLine = 5, endLine = 6, functionName = "applyIncrement")

        val result = applyAndRead(edits, file)

        assertTrue("fun applyIncrement(" in result, "Should contain extracted function. Got:\n$result")
        assertCompiles(projectDir)
    }
}

// =============================================================================
// ExtractVariable End-to-End Tests
// =============================================================================

class ExtractVariableEndToEndTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `extract expression inside when branch`() {
        val projectDir = createProject(tempDir, mapOf(
            "Foo.kt" to """
                fun classify(x: Int): String {
                    return when {
                        x > 100 -> "big: " + x.toString()
                        x > 0 -> "small: " + x.toString()
                        else -> "non-positive"
                    }
                }
            """.trimIndent()
        ))

        val analysis = analyzeProject(projectDir)
        val file = projectDir.resolve("src/main/kotlin/Foo.kt")
        // cursor on x.toString() in the first branch
        val edits = ExtractVariableRefactoring(analysis).extract(file, line = 3, col = 26, variableName = "label")

        val result = applyAndRead(edits, file)

        assertTrue("val label" in result, "Should contain extracted variable. Got:\n$result")
        assertCompiles(projectDir)
    }

    @Test
    fun `extract sub-expression of binary expression`() {
        val projectDir = createProject(tempDir, mapOf(
            "Foo.kt" to """
                fun compute(a: Int, b: Int, c: Int): Int {
                    return a + b * c
                }
            """.trimIndent()
        ))

        val analysis = analyzeProject(projectDir)
        val file = projectDir.resolve("src/main/kotlin/Foo.kt")
        // cursor on `b * c` (col points to `b`)
        val edits = ExtractVariableRefactoring(analysis).extract(file, line = 2, col = 16, variableName = "product")

        val result = applyAndRead(edits, file)

        assertTrue("val product" in result, "Should contain extracted variable. Got:\n$result")
        assertTrue("a + product" in result || "a +product" in result,
            "Original expression should use the variable. Got:\n$result")

        assertCompiles(projectDir)
    }

    @Test
    fun `extract string template expression`() {
        val projectDir = createProject(tempDir, mapOf(
            "Foo.kt" to """
                fun greet(user: String, place: String): String {
                    return "Hello " + user + " at " + place
                }
            """.trimIndent()
        ))

        val analysis = analyzeProject(projectDir)
        val file = projectDir.resolve("src/main/kotlin/Foo.kt")
        // cursor on the entire concatenation expression — point at the first "Hello "
        val edits = ExtractVariableRefactoring(analysis).extract(file, line = 2, col = 12, variableName = "message")

        val result = applyAndRead(edits, file)

        assertTrue("val message" in result, "Should contain extracted variable. Got:\n$result")

        assertCompiles(projectDir)
    }
}

// =============================================================================
// Inline End-to-End Tests
// =============================================================================

class InlineEndToEndTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `inline variable used multiple times`() {
        val projectDir = createProject(tempDir, mapOf(
            "Foo.kt" to """
                fun process(x: Int): Int {
                    val doubled = x * 2
                    println(doubled)
                    return doubled
                }
            """.trimIndent()
        ))

        val analysis = analyzeProject(projectDir)
        val file = projectDir.resolve("src/main/kotlin/Foo.kt")
        val edits = InlineRefactoring(analysis).inline(SourceLocation(file, 2, 9))

        val result = applyAndRead(edits, file)

        assertFalse("val doubled" in result, "Variable declaration should be removed. Got:\n$result")
        assertTrue("x * 2" in result, "Initializer should replace usages. Got:\n$result")

        assertCompiles(projectDir)
    }

    @Test
    fun `inline function with named arguments at call site`() {
        val projectDir = createProject(tempDir, mapOf(
            "Foo.kt" to """
                fun create(name: String, age: Int): String {
                    return name + ":" + age
                }

                fun main() {
                    val result = create(age = 25, name = "Bob")
                    println(result)
                }
            """.trimIndent()
        ))

        val analysis = analyzeProject(projectDir)
        val file = projectDir.resolve("src/main/kotlin/Foo.kt")
        // inline the `create` function
        val edits = InlineRefactoring(analysis).inline(SourceLocation(file, 1, 5))

        val result = applyAndRead(edits, file)

        // After inline, the body should have "Bob" for name and 25 for age
        assertTrue("\"Bob\"" in result && "25" in result, "Arguments should be correctly substituted. Got:\n$result")
        assertCompiles(projectDir)
    }

    @Test
    fun `inline function with string template in body`() {
        val projectDir = createProject(tempDir, mapOf(
            "Foo.kt" to """
                fun greet(name: String): String {
                    return "Hello, ${'$'}name!"
                }

                fun main() {
                    val msg = greet("World")
                    println(msg)
                }
            """.trimIndent()
        ))

        val analysis = analyzeProject(projectDir)
        val file = projectDir.resolve("src/main/kotlin/Foo.kt")
        val edits = InlineRefactoring(analysis).inline(SourceLocation(file, 1, 5))

        val result = applyAndRead(edits, file)

        assertCompiles(projectDir, "Inlined code with string template should compile")
    }

    @Test
    fun `inline var that is reassigned should refuse`() {
        val projectDir = createProject(tempDir, mapOf(
            "Foo.kt" to """
                fun process(): Int {
                    var x = 1
                    x = x + 1
                    return x
                }
            """.trimIndent()
        ))

        val analysis = analyzeProject(projectDir)
        val file = projectDir.resolve("src/main/kotlin/Foo.kt")

        assertThrows<IllegalArgumentException>("Should refuse to inline a reassigned var") {
            InlineRefactoring(analysis).inline(SourceLocation(file, 2, 9))
        }
    }
}

// =============================================================================
// ChangeSignature End-to-End Tests
// =============================================================================

class ChangeSignatureEndToEndTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `change signature with named arguments at call site`() {
        val projectDir = createProject(tempDir, mapOf(
            "Foo.kt" to """
                fun create(name: String, age: Int): String {
                    return "${'$'}name:${'$'}age"
                }

                fun main() {
                    val r = create(age = 25, name = "Alice")
                    println(r)
                }
            """.trimIndent()
        ))

        val analysis = analyzeProject(projectDir)
        val file = projectDir.resolve("src/main/kotlin/Foo.kt")
        // Reorder: age first, then name
        val edits = ChangeSignatureRefactoring(analysis).changeSignature(
            file, line = 1, col = 5,
            newParams = listOf(
                ChangeSignatureRefactoring.ParameterSpec("age", "Int"),
                ChangeSignatureRefactoring.ParameterSpec("name", "String"),
            )
        )

        val result = applyAndRead(edits, file)

        assertTrue("fun create(age: Int, name: String)" in result, "Declaration should be reordered. Got:\n$result")
        assertCompiles(projectDir, "Reordered signature with named arguments should compile")
    }

    @Test
    fun `change signature preserves trailing lambda`() {
        val projectDir = createProject(tempDir, mapOf(
            "Foo.kt" to """
                fun execute(label: String, block: () -> Unit) {
                    println(label)
                    block()
                }

                fun main() {
                    execute("test") {
                        println("running")
                    }
                }
            """.trimIndent()
        ))

        val analysis = analyzeProject(projectDir)
        val file = projectDir.resolve("src/main/kotlin/Foo.kt")
        // Add a parameter before the lambda
        val edits = ChangeSignatureRefactoring(analysis).changeSignature(
            file, line = 1, col = 5,
            newParams = listOf(
                ChangeSignatureRefactoring.ParameterSpec("label", "String"),
                ChangeSignatureRefactoring.ParameterSpec("verbose", "Boolean", "false"),
                ChangeSignatureRefactoring.ParameterSpec("block", "() -> Unit"),
            )
        )

        val result = applyAndRead(edits, file)

        assertTrue("verbose: Boolean = false" in result, "New parameter should be added. Got:\n$result")
        assertCompiles(projectDir)
    }

    @Test
    fun `change signature adds parameter with default value, existing calls untouched`() {
        val projectDir = createProject(tempDir, mapOf(
            "Foo.kt" to """
                fun format(value: Int): String {
                    return value.toString()
                }

                fun main() {
                    println(format(42))
                    println(format(100))
                }
            """.trimIndent()
        ))

        val analysis = analyzeProject(projectDir)
        val file = projectDir.resolve("src/main/kotlin/Foo.kt")
        val edits = ChangeSignatureRefactoring(analysis).changeSignature(
            file, line = 1, col = 5,
            newParams = listOf(
                ChangeSignatureRefactoring.ParameterSpec("value", "Int"),
                ChangeSignatureRefactoring.ParameterSpec("prefix", "String", "\"\""),
            )
        )

        val result = applyAndRead(edits, file)

        assertTrue("prefix: String = \"\"" in result, "New param with default should be added. Got:\n$result")
        assertTrue("format(42)" in result, "Existing call should be untouched. Got:\n$result")
    }
}

// =============================================================================
// SafeDelete End-to-End Tests
// =============================================================================

class SafeDeleteEndToEndTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `safe delete class used as type reference refuses`() {
        val projectDir = createProject(tempDir, mapOf(
            "Foo.kt" to """
                class Config(val name: String)

                fun loadConfig(): Config {
                    return Config("default")
                }
            """.trimIndent()
        ))

        val analysis = analyzeProject(projectDir)
        val file = projectDir.resolve("src/main/kotlin/Foo.kt")

        assertThrows<IllegalArgumentException>("Should refuse to delete class used as type reference") {
            SafeDeleteRefactoring(analysis).delete(file, line = 1, col = 7)
        }
    }

    @Test
    fun `safe delete with multi-file usage prevents deletion`() {
        val projectDir = createProject(tempDir, mapOf(
            "pkg/Utils.kt" to """
                package pkg

                fun helperFunction(): Int = 42
            """.trimIndent(),
            "pkg/Main.kt" to """
                package pkg

                fun main() {
                    println(helperFunction())
                }
            """.trimIndent()
        ))

        val analysis = analyzeProject(projectDir)
        val file = projectDir.resolve("src/main/kotlin/pkg/Utils.kt")

        assertThrows<IllegalArgumentException>("Should refuse to delete function used in another file") {
            SafeDeleteRefactoring(analysis).delete(file, line = 3, col = 5)
        }
    }

    @Test
    fun `safe delete unused function succeeds and compiles`() {
        val projectDir = createProject(tempDir, mapOf(
            "Foo.kt" to """
                fun usedFunction(): Int = 42

                fun unusedFunction(): String = "never called"

                fun main() {
                    println(usedFunction())
                }
            """.trimIndent()
        ))

        val analysis = analyzeProject(projectDir)
        val file = projectDir.resolve("src/main/kotlin/Foo.kt")
        val edits = SafeDeleteRefactoring(analysis).delete(file, line = 3, col = 5)

        val result = applyAndRead(edits, file)

        assertFalse("unusedFunction" in result, "Unused function should be deleted. Got:\n$result")
        assertTrue("usedFunction" in result, "Used function should remain. Got:\n$result")
        assertTrue("fun main()" in result, "Main function should remain. Got:\n$result")

        assertCompiles(projectDir)
    }
}

// =============================================================================
// Move End-to-End Tests
// =============================================================================

class MoveEndToEndTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `move function between packages updates imports`() {
        val projectDir = createProject(tempDir, mapOf(
            "pkg/a/Utils.kt" to """
                package pkg.a

                fun compute(x: Int): Int = x * 2
            """.trimIndent(),
            "pkg/b/Main.kt" to """
                package pkg.b

                import pkg.a.compute

                fun main() {
                    println(compute(21))
                }
            """.trimIndent()
        ))

        val analysis = analyzeProject(projectDir)
        val sourceRoots = GradleProjectDiscovery(projectDir).discoverSourceRoots()
        val output = MoveRefactoring(analysis).move("pkg.a.compute", "pkg.c", sourceRoots)
        output.writeNewFiles()
        SourceRewriter.applyEdits(output.edits)

        val mainResult = projectDir.resolve("src/main/kotlin/pkg/b/Main.kt").readText()

        assertTrue("import pkg.c.compute" in mainResult, "Import should be updated to new package. Got:\n$mainResult")
        assertFalse("import pkg.a.compute" in mainResult, "Old import should be removed. Got:\n$mainResult")
    }

    @Test
    fun `move class with dependencies transfers required imports`() {
        val projectDir = createProject(tempDir, mapOf(
            "pkg/a/Types.kt" to """
                package pkg.a

                data class Config(val name: String, val timeout: Int)
            """.trimIndent(),
            "pkg/a/Processor.kt" to """
                package pkg.a

                class Processor(private val config: Config) {
                    fun run(): String = config.name
                }
            """.trimIndent(),
            "pkg/b/Main.kt" to """
                package pkg.b

                import pkg.a.Config
                import pkg.a.Processor

                fun main() {
                    val p = Processor(Config("test", 30))
                    println(p.run())
                }
            """.trimIndent()
        ))

        val analysis = analyzeProject(projectDir)
        val sourceRoots = GradleProjectDiscovery(projectDir).discoverSourceRoots()
        val output = MoveRefactoring(analysis).move("pkg.a.Processor", "pkg.c", sourceRoots)
        output.writeNewFiles()
        SourceRewriter.applyEdits(output.edits)

        // The moved Processor class needs to import Config from pkg.a
        val movedFile = projectDir.resolve("src/main/kotlin/pkg/c/Processor.kt")
        assertTrue(movedFile.toFile().exists(), "Target file should be created")
        val movedResult = movedFile.readText()
        assertTrue("import pkg.a.Config" in movedResult, "Moved file should import Config. Got:\n$movedResult")

        assertCompiles(projectDir)
    }
}

// =============================================================================
// ExtractConstant End-to-End Tests
// =============================================================================

class ExtractConstantEndToEndTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `extract constant inside class creates companion object entry`() {
        val projectDir = createProject(tempDir, mapOf(
            "Foo.kt" to """
                class HttpClient {
                    fun getTimeout(): Int {
                        return 5000
                    }
                }
            """.trimIndent()
        ))

        val analysis = analyzeProject(projectDir)
        val file = projectDir.resolve("src/main/kotlin/Foo.kt")
        val edits = ExtractConstantRefactoring(analysis).extract(file, line = 3, col = 16, constantName = "DEFAULT_TIMEOUT")

        val result = applyAndRead(edits, file)

        assertTrue("DEFAULT_TIMEOUT" in result, "Should reference the constant. Got:\n$result")
        assertTrue("5000" in result, "Should contain the value. Got:\n$result")
        assertTrue("companion object" in result || "const val" in result,
            "Should have companion object or const val. Got:\n$result")
    }

    @Test
    fun `extract string with interpolation should refuse or handle correctly`() {
        val projectDir = createProject(tempDir, mapOf(
            "Foo.kt" to """
                fun greet(name: String): String {
                    return "Hello, ${'$'}name!"
                }
            """.trimIndent()
        ))

        val analysis = analyzeProject(projectDir)
        val file = projectDir.resolve("src/main/kotlin/Foo.kt")

        // String with interpolation can't be const val — should either refuse or not use const
        assertThrows<IllegalArgumentException>("Should refuse to extract interpolated string as constant") {
            ExtractConstantRefactoring(analysis).extract(file, line = 2, col = 12, constantName = "GREETING")
        }
    }
}
