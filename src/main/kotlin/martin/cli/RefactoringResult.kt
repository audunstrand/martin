package martin.cli

import kotlinx.serialization.Serializable

/**
 * Structured JSON response for refactoring operations.
 * Used when --format json is specified.
 */
@Serializable
data class RefactoringResult(
    val success: Boolean,
    val command: String,
    val edits: List<EditInfo> = emptyList(),
    val filesModified: Int = 0,
    val durationMs: Long = 0,
    val error: String? = null,
    val diagnostics: List<DiagnosticInfo> = emptyList(),
) {
    @Serializable
    data class EditInfo(
        val file: String,
        val offset: Int,
        val length: Int,
        val replacement: String,
    )

    @Serializable
    data class DiagnosticInfo(
        val file: String? = null,
        val line: Int? = null,
        val message: String,
    )
}
