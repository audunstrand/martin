package martin.refactoring

/**
 * Structured exception for refactoring failures.
 * Provides machine-readable error info for JSON/MCP consumers.
 */
class RefactoringException(
    message: String,
    val type: String? = null,
    val file: String? = null,
    val line: Int? = null,
    val col: Int? = null,
    val usages: List<UsageLocation> = emptyList(),
    cause: Throwable? = null,
) : RuntimeException(message, cause) {

    data class UsageLocation(
        val file: String,
        val line: Int,
        val text: String,
    )
}
