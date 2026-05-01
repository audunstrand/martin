package martin.metrics

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

/**
 * SQLite-backed storage for refactoring metrics.
 * Database is stored at <projectDir>/.martin/metrics.db
 */
class MetricsStore(private val projectDir: Path) {

    private val dbPath: Path = projectDir.resolve(".martin/metrics.db")

    private fun connect(): Connection {
        if (!dbPath.parent.exists()) {
            dbPath.parent.createDirectories()
        }
        val conn = DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}")
        conn.createStatement().execute(
            """
            CREATE TABLE IF NOT EXISTS refactorings (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                type            TEXT NOT NULL,
                success         INTEGER NOT NULL,
                error           TEXT,
                duration_ms     INTEGER NOT NULL,
                files_modified  INTEGER NOT NULL,
                edits_count     INTEGER NOT NULL,
                timestamp       TEXT NOT NULL
            )
            """.trimIndent()
        )
        return conn
    }

    fun record(event: RefactoringEvent) {
        connect().use { conn ->
            val stmt = conn.prepareStatement(
                """
                INSERT INTO refactorings (type, success, error, duration_ms, files_modified, edits_count, timestamp)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            )
            stmt.setString(1, event.type)
            stmt.setInt(2, if (event.success) 1 else 0)
            stmt.setString(3, event.error)
            stmt.setLong(4, event.durationMs)
            stmt.setInt(5, event.filesModified)
            stmt.setInt(6, event.editsCount)
            stmt.setString(7, event.timestamp.toString())
            stmt.executeUpdate()
        }
    }

    fun queryAll(): List<RefactoringEvent> = querySince(null)

    fun querySince(since: Instant?): List<RefactoringEvent> {
        connect().use { conn ->
            val sql = if (since != null) {
                "SELECT * FROM refactorings WHERE timestamp >= ? ORDER BY timestamp DESC"
            } else {
                "SELECT * FROM refactorings ORDER BY timestamp DESC"
            }
            val stmt = conn.prepareStatement(sql)
            if (since != null) {
                stmt.setString(1, since.toString())
            }
            val rs = stmt.executeQuery()
            val results = mutableListOf<RefactoringEvent>()
            while (rs.next()) {
                results.add(
                    RefactoringEvent(
                        id = rs.getLong("id"),
                        type = rs.getString("type"),
                        success = rs.getInt("success") == 1,
                        error = rs.getString("error"),
                        durationMs = rs.getLong("duration_ms"),
                        filesModified = rs.getInt("files_modified"),
                        editsCount = rs.getInt("edits_count"),
                        timestamp = Instant.parse(rs.getString("timestamp")),
                    )
                )
            }
            return results
        }
    }

    fun summary(since: Instant? = null): RefactoringSummary {
        val events = querySince(since)
        if (events.isEmpty()) {
            return RefactoringSummary(0, 0, 0, 0, 0, 0, emptyList())
        }

        val byType = events.groupBy { it.type }.map { (type, typeEvents) ->
            TypeSummary(
                type = type,
                count = typeEvents.size,
                successRate = typeEvents.count { it.success }.toDouble() / typeEvents.size,
                avgDurationMs = typeEvents.map { it.durationMs }.average().toLong(),
                totalFilesModified = typeEvents.sumOf { it.filesModified },
                totalEdits = typeEvents.sumOf { it.editsCount },
            )
        }.sortedByDescending { it.count }

        return RefactoringSummary(
            total = events.size,
            succeeded = events.count { it.success },
            failed = events.count { !it.success },
            totalFilesModified = events.sumOf { it.filesModified },
            totalEdits = events.sumOf { it.editsCount },
            avgDurationMs = events.map { it.durationMs }.average().toLong(),
            byType = byType,
        )
    }
}
