package martin.metrics

import java.time.Instant

/**
 * A single recorded refactoring event.
 */
data class RefactoringEvent(
    val id: Long = 0,
    val type: String,
    val success: Boolean,
    val error: String? = null,
    val durationMs: Long,
    val filesModified: Int,
    val editsCount: Int,
    val timestamp: Instant = Instant.now(),
)

/**
 * Aggregated summary of refactoring events.
 */
data class RefactoringSummary(
    val total: Int,
    val succeeded: Int,
    val failed: Int,
    val totalFilesModified: Int,
    val totalEdits: Int,
    val avgDurationMs: Long,
    val byType: List<TypeSummary>,
)

data class TypeSummary(
    val type: String,
    val count: Int,
    val successRate: Double,
    val avgDurationMs: Long,
    val totalFilesModified: Int,
    val totalEdits: Int,
)
