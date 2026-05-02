package martin.daemon

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import martin.compiler.GradleProjectDiscovery
import martin.compiler.KotlinAnalyzer
import martin.compiler.AnalysisResult
import martin.metrics.MetricsStore
import martin.metrics.RefactoringEvent
import martin.refactoring.*
import martin.refactoring.convert.*
import martin.refactoring.core.*
import martin.refactoring.core.InlineRefactoring.SourceLocation
import martin.refactoring.extract.*
import martin.refactoring.restructure.*
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
                val edits = executeRefactoring(request, analysis)
                val filesModified = if (edits.isNotEmpty()) SourceRewriter.applyEdits(edits) else 0
                val duration = System.currentTimeMillis() - start

                recordMetrics(request.command, true, duration, filesModified, edits.size)
                writer.println(json.encodeToString(DaemonResponse.serializer(), DaemonResponse(
                    success = true,
                    message = "${request.command}: ${edits.size} edits across $filesModified files (${duration}ms)",
                    edits = edits.size,
                    filesModified = filesModified,
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

    private fun executeRefactoring(request: DaemonRequest, analysis: AnalysisResult): List<martin.rewriter.TextEdit> {
        val file = request.file?.let { Path(it) }
        return when (request.command) {
            "rename" -> RenameRefactoring(analysis).rename(file!!, request.line!!, request.col!!, request.newName!!)
            "extract-function" -> ExtractFunctionRefactoring(analysis).extract(file!!, request.startLine!!, request.endLine!!, request.name!!)
            "extract-variable" -> ExtractVariableRefactoring(analysis).extract(file!!, request.line!!, request.col!!, request.name!!)
            "inline" -> InlineRefactoring(analysis).inline(SourceLocation(file!!, request.line!!, request.col!!))
            "move" -> {
                val sourceRoots = GradleProjectDiscovery(projectDir).discoverSourceRoots()
                val output = MoveRefactoring(analysis).move(request.symbol!!, request.toPackage!!, sourceRoots)
                output.writeNewFiles()
                output.edits
            }
            "change-signature" -> {
                val params = request.params!!.split(",").map { param ->
                    val parts = param.trim().split(":")
                    val nameStr = parts[0].trim()
                    val typeAndDefault = parts[1].trim()
                    val typeStr = if (typeAndDefault.contains("=")) typeAndDefault.substringBefore("=").trim() else typeAndDefault
                    val default = if (param.contains("=")) param.substringAfter("=").trim() else null
                    ChangeSignatureRefactoring.ParameterSpec(nameStr, typeStr, default)
                }
                ChangeSignatureRefactoring(analysis).changeSignature(file!!, request.line!!, request.col!!, params)
            }
            "convert-to-expression-body" -> ConvertToExpressionBodyRefactoring(analysis).convert(file!!, request.line!!, request.col!!)
            "convert-to-block-body" -> ConvertToBlockBodyRefactoring(analysis).convert(file!!, request.line!!, request.col!!)
            "add-named-arguments" -> AddNamedArgumentsRefactoring(analysis).convert(file!!, request.line!!, request.col!!)
            "extract-constant" -> ExtractConstantRefactoring(analysis).extract(file!!, request.line!!, request.col!!, request.name!!)
            "safe-delete" -> SafeDeleteRefactoring(analysis).delete(file!!, request.line!!, request.col!!)
            "convert-property-to-function" -> ConvertPropertyToFunctionRefactoring(analysis).convert(file!!, request.line!!, request.col!!)
            "extract-parameter" -> ExtractParameterRefactoring(analysis).extract(file!!, request.line!!, request.col!!, request.name!!)
            "introduce-parameter-object" -> {
                val paramNames = request.paramNames?.split(",")?.map { it.trim() } ?: emptyList()
                IntroduceParameterObjectRefactoring(analysis).introduce(file!!, request.line!!, request.col!!, request.name!!, paramNames)
            }
            "extract-interface" -> {
                val methods = request.methods?.split(",")?.map { it.trim() } ?: emptyList()
                val output = ExtractInterfaceRefactoring(analysis).extract(file!!, request.line!!, request.col!!, request.name!!, methods)
                output.writeNewFiles()
                output.edits
            }
            "extract-superclass" -> {
                val members = request.members?.split(",")?.map { it.trim() } ?: emptyList()
                val output = ExtractSuperclassRefactoring(analysis).extract(file!!, request.line!!, request.col!!, request.name!!, members)
                output.writeNewFiles()
                output.edits
            }
            "pull-up-method" -> PullUpMethodRefactoring(analysis).pullUp(file!!, request.line!!, request.col!!)
            "replace-constructor-with-factory" -> ReplaceConstructorWithFactoryRefactoring(analysis).replace(file!!, request.line!!, request.col!!, request.name ?: "create")
            "convert-to-data-class" -> ConvertToDataClassRefactoring(analysis).convert(file!!, request.line!!, request.col!!)
            "convert-to-extension-function" -> ConvertToExtensionFunctionRefactoring(analysis).convert(file!!, request.line!!, request.col!!)
            "convert-to-sealed-class" -> ConvertToSealedClassRefactoring(analysis).convert(file!!, request.line!!, request.col!!)
            "encapsulate-field" -> EncapsulateFieldRefactoring(analysis).encapsulate(file!!, request.line!!, request.col!!)
            "type-migration" -> TypeMigrationRefactoring(analysis).migrate(file!!, request.line!!, request.col!!, request.newType!!)
            "move-statements-into-function" -> MoveStatementsIntoFunctionRefactoring(analysis).move(
                file!!, request.functionLine!!, request.functionCol!!, request.startLine!!, request.endLine!!
            )
            else -> error("Unknown command: ${request.command}")
        }
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
    val name: String? = null,
    val newName: String? = null,
    val startLine: Int? = null,
    val endLine: Int? = null,
    val symbol: String? = null,
    val toPackage: String? = null,
    val params: String? = null,
    val newType: String? = null,
    val targetFunction: String? = null,
    val functionLine: Int? = null,
    val functionCol: Int? = null,
    val paramNames: String? = null,
    val methods: String? = null,
    val members: String? = null,
)

@Serializable
data class DaemonResponse(
    val success: Boolean,
    val message: String,
    val edits: Int = 0,
    val filesModified: Int = 0,
    val durationMs: Long = 0,
)
