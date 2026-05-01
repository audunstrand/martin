package martin.daemon

import kotlinx.serialization.json.Json
import java.io.PrintWriter
import java.net.Socket
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Client for communicating with a running Martin daemon.
 * Returns null if no daemon is running.
 */
object DaemonClient {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Send a request to the daemon. Returns the response, or null if no daemon is reachable.
     */
    fun send(projectDir: Path, request: DaemonRequest): DaemonResponse? {
        val port = readPort(projectDir) ?: return null
        return try {
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 30_000
                val writer = PrintWriter(socket.getOutputStream(), true)
                val reader = socket.getInputStream().bufferedReader()
                writer.println(json.encodeToString(DaemonRequest.serializer(), request))
                val line = reader.readLine() ?: return null
                json.decodeFromString<DaemonResponse>(line)
            }
        } catch (_: Exception) {
            // Daemon not reachable — port file is stale
            null
        }
    }

    private fun readPort(projectDir: Path): Int? {
        val portFile = projectDir.resolve(".martin/daemon.port")
        if (!portFile.exists()) return null
        return try {
            portFile.readText().trim().toInt()
        } catch (_: Exception) {
            null
        }
    }
}
