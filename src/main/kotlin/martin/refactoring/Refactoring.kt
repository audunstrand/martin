package martin.refactoring

import martin.compiler.AnalysisResult
import martin.rewriter.TextEdit
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * A refactoring operation that can be discovered, described, and executed uniformly.
 *
 * Every concrete refactoring implements this interface, providing:
 * - [name]: a kebab-case identifier (e.g. "rename", "extract-function")
 * - [description]: a human-readable summary (used in CLI help and MCP tool descriptions)
 * - [params]: a declarative schema of the extra parameters this refactoring accepts
 * - [execute]: the actual refactoring logic
 *
 * The standard `file`, `line`, and `col` parameters are part of [RefactoringContext]
 * and do not need to be declared in [params].
 */
interface Refactoring {

    /** Kebab-case identifier, e.g. "rename", "extract-function". */
    val name: String

    /** One-line description of what this refactoring does. */
    val description: String

    /** Extra parameters beyond the standard file/line/col. */
    val params: List<ParamDef>

    /**
     * Execute the refactoring and return the result.
     *
     * Implementations must NOT write files to disk — all mutations are expressed
     * as [TextEdit]s or new-file entries in [RefactoringOutput].
     */
    fun execute(ctx: RefactoringContext): RefactoringOutput
}

/**
 * Declares one parameter that a [Refactoring] accepts.
 */
data class ParamDef(
    /** Parameter name (used as key in [RefactoringContext.args]). */
    val name: String,
    /** The value type. */
    val type: ParamType,
    /** Human-readable description. */
    val description: String,
    /** Whether this parameter is required. */
    val required: Boolean = true,
    /** Default value (as a string) when [required] is false. */
    val default: String? = null,
)

enum class ParamType {
    STRING,
    INT,
    /** Comma-separated list of strings. */
    STRING_LIST,
}

/**
 * Input context for a refactoring execution.
 *
 * [file], [line], and [col] locate the cursor position. Some refactorings
 * (e.g. Move) don't use these and instead rely solely on [args].
 */
data class RefactoringContext(
    val analysis: AnalysisResult,
    /** Absolute path to the target file (may be unused by some refactorings). */
    val file: Path,
    /** 1-based line number. */
    val line: Int,
    /** 1-based column number. */
    val col: Int,
    /** Extra parameters keyed by [ParamDef.name]. */
    val args: Map<String, String> = emptyMap(),
    /** Project root directory (used by refactorings that need source root discovery). */
    val projectDir: Path = file.parent,
) {
    /** Convenience: get a required string arg. */
    fun string(key: String): String =
        args[key] ?: throw IllegalArgumentException("Missing required argument: $key")

    /** Convenience: get a required int arg. */
    fun int(key: String): Int =
        args[key]?.toIntOrNull() ?: throw IllegalArgumentException("Missing or invalid integer argument: $key")

    /** Convenience: get a comma-separated list arg. */
    fun stringList(key: String): List<String> =
        args[key]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: throw IllegalArgumentException("Missing required argument: $key")

    /** Convenience: get an optional string arg. */
    fun stringOrNull(key: String): String? = args[key]

    /** Convenience: get an optional int arg. */
    fun intOrNull(key: String): Int? = args[key]?.toIntOrNull()
}

/**
 * The result of a refactoring execution.
 *
 * [edits] contains modifications to existing files.
 * [newFiles] contains files that should be created (path → content).
 */
data class RefactoringOutput(
    val edits: List<TextEdit>,
    val newFiles: Map<Path, String> = emptyMap(),
) {
    companion object {
        /** Shorthand for a result with only edits. */
        fun edits(edits: List<TextEdit>) = RefactoringOutput(edits)

        /** Empty result (no changes). */
        val EMPTY = RefactoringOutput(emptyList())
    }

    /** Write [newFiles] to disk. Call this when not in dry-run mode. */
    fun writeNewFiles() {
        for ((path, content) in newFiles) {
            path.parent?.createDirectories()
            path.writeText(content)
        }
    }
}
