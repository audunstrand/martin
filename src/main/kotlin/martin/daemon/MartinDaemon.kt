package martin.daemon

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import martin.compiler.KotlinAnalyzer
import martin.compiler.AnalysisResult
import martin.metrics.MetricsStore
import martin.metrics.RefactoringEvent
import martin.refactoring.*
import martin.rewriter.SourceRewriter
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText

/**
 * Long-running daemon that keeps the Kotlin compiler environment warm.
 * Listens on a local TCP port and accepts JSON refactoring requests.
 *
 * Eliminates the ~3s KotlinCoreEnvironment creation overhead on every invocation.
 */
class MartinDaemon(private val projectDir: Path) {

    private val portFile = projectDir.resolve(".martin/daemon.port")
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var running = true

    fun start() {
        val analyzer = KotlinAnalyzer.create(projectDir)
        println("Daemon: warming up compiler environment...")
        analyzer.warmUp()
        // Initial analysis to verify everything works
        val initial = analyzer.analyze()
        println("Daemon: ready, ${initial.files.size} files indexed")
        initial.close()

        val server = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val port = server.localPort

        projectDir.resolve(".martin").toFile().mkdirs()
        portFile.writeText(port.toString())
        println("Daemon: listening on port $port (pid ${ProcessHandle.current().pid()})")

        Runtime.getRuntime().addShutdownHook(Thread {
            portFile.deleteIfExists()
            analyzer.disposeEnvironment()
        })

        try {
            while (running) {
                val socket = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                try {
                    handleConnection(socket, analyzer)
                } catch (e: Exception) {
                    System.err.println("Daemon: error handling request: ${e.message}")
                }
            }
        } finally {
            portFile.deleteIfExists()
            analyzer.disposeEnvironment()
            server.close()
            println("Daemon: stopped")
        }
    }

    private fun handleConnection(socket: Socket, analyzer: KotlinAnalyzer) {
        socket.use { s ->
            val reader = s.getInputStream().bufferedReader()
            val writer = PrintWriter(s.getOutputStream(), true)
            val line = reader.readLine() ?: return

            val request = json.decodeFromString<DaemonRequest>(line)

            if (request.command == "stop") {
                writer.println(json.encodeToString(DaemonResponse.serializer(), DaemonResponse(success = true, message = "stopping")))
                running = false
                return
            }

            if (request.command == "status") {
                writer.println(json.encodeToString(DaemonResponse.serializer(), DaemonResponse(
                    success = true, message = "running"
                )))
                return
            }

            val start = System.currentTimeMillis()
            // Re-analyze to pick up file changes (fast with warm environment)
            val analysis = analyzer.analyze()
            try {
                val output = executeRefactoring(request, analysis)
                output.writeNewFiles()
                val edits = output.edits
                val filesModified = if (edits.isNotEmpty()) SourceRewriter.applyEdits(edits) else 0
                val totalFiles = filesModified + output.newFiles.size
                val duration = System.currentTimeMillis() - start

                recordMetrics(request.command, true, duration, totalFiles, edits.size)
                writer.println(json.encodeToString(DaemonResponse.serializer(), DaemonResponse(
                    success = true,
                    message = "${request.command}: ${edits.size} edits across $totalFiles files (${duration}ms)",
                    edits = edits.size,
                    filesModified = totalFiles,
                    durationMs = duration,
                )))
            } catch (e: Exception) {
                val duration = System.currentTimeMillis() - start
                recordMetrics(request.command, false, duration, 0, 0, e.message)
                writer.println(json.encodeToString(DaemonResponse.serializer(), DaemonResponse(
                    success = false, message = e.message ?: "unknown error"
                )))
            } finally {
                analysis.close()
            }
        }
    }

    private fun executeRefactoring(request: DaemonRequest, analysis: AnalysisResult): RefactoringOutput {
        val entry = RefactoringRegistry.find(request.command)
            ?: error("Unknown command: ${request.command}")

        val refactoring = entry.factory(analysis)
        val file = request.file?.let { Path(it) } ?: projectDir
        val ctx = RefactoringContext(
            analysis = analysis,
            file = file,
            line = request.line ?: 0,
            col = request.col ?: 0,
            args = request.args,
            projectDir = projectDir,
        )
        return refactoring.execute(ctx)
    }

    private fun recordMetrics(type: String, success: Boolean, durationMs: Long, filesModified: Int, editsCount: Int, error: String? = null) {
        try {
            MetricsStore(projectDir).record(RefactoringEvent(
                type = type, success = success, durationMs = durationMs,
                filesModified = filesModified, editsCount = editsCount, error = error,
            ))
        } catch (_: Exception) {}
    }
}

@Serializable
data class DaemonRequest(
    val command: String,
    val file: String? = null,
    val line: Int? = null,
    val col: Int? = null,
    val args: Map<String, String> = emptyMap(),
)

@Serializable
data class DaemonResponse(
    val success: Boolean,
    val message: String,
    val edits: Int = 0,
    val filesModified: Int = 0,
    val durationMs: Long = 0,
)
