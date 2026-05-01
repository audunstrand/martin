package martin.compiler

import org.jetbrains.kotlin.cli.common.messages.MessageRenderer
import org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoots
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import java.io.File
import java.nio.file.Path

/**
 * Wraps the Kotlin compiler frontend to parse and analyze a project.
 */
class KotlinAnalyzer private constructor(
    private val projectDir: Path,
    private val sourceRoots: List<Path>,
    private val classpathEntries: List<File>,
) {
    companion object {
        val ORIGINAL_FILE_KEY = org.jetbrains.kotlin.com.intellij.openapi.util.Key.create<Path>("martin.originalFilePath")

        fun create(projectDir: Path): KotlinAnalyzer {
            val discovery = GradleProjectDiscovery(projectDir)
            val sourceRoots = discovery.discoverSourceRoots()
            val classpath = discovery.discoverClasspath()
            return KotlinAnalyzer(projectDir, sourceRoots, classpath)
        }
    }

    // Cached environment for daemon mode — reused across analyze() calls.
    private var cachedEnvironment: KotlinCoreEnvironment? = null
    private var cachedDisposable: org.jetbrains.kotlin.com.intellij.openapi.Disposable? = null

    fun analyze(): AnalysisResult {
        val (environment, disposable, ownsEnvironment) = getOrCreateEnvironment()

        try {
            val psiFactory = KtPsiFactory(environment.project)
            val ktFiles = sourceRoots.flatMap { root ->
                root.toFile().walkTopDown()
                    .filter { it.extension == "kt" }
                    .map { file ->
                        psiFactory.createFile(file.name, file.readText()).apply {
                            putUserData(ORIGINAL_FILE_KEY, file.toPath())
                        }
                    }
                    .toList()
            }

            val bindingContext = TopLevelAnalyzer(environment).analyzeFiles(ktFiles)

            return AnalysisResult(
                files = ktFiles,
                bindingContext = bindingContext,
                environment = environment,
                disposable = disposable,
                ownsDisposable = ownsEnvironment,
            )
        } catch (e: Exception) {
            if (ownsEnvironment) Disposer.dispose(disposable)
            throw e
        }
    }

    /**
     * Enable environment caching for daemon mode.
     * Creates the environment once and reuses it for subsequent analyze() calls.
     * The caller is responsible for calling [disposeEnvironment] when done.
     */
    fun warmUp() {
        if (cachedEnvironment != null) return
        val disposable = Disposer.newDisposable("martin-daemon")
        cachedEnvironment = createEnvironment(disposable)
        cachedDisposable = disposable
    }

    /**
     * Dispose the cached environment. Call when shutting down daemon mode.
     */
    fun disposeEnvironment() {
        cachedDisposable?.let { Disposer.dispose(it) }
        cachedEnvironment = null
        cachedDisposable = null
    }

    private data class EnvironmentInfo(
        val environment: KotlinCoreEnvironment,
        val disposable: org.jetbrains.kotlin.com.intellij.openapi.Disposable,
        val ownsEnvironment: Boolean,
    )

    private fun getOrCreateEnvironment(): EnvironmentInfo {
        val cached = cachedEnvironment
        val cachedDisp = cachedDisposable
        if (cached != null && cachedDisp != null) {
            return EnvironmentInfo(cached, cachedDisp, ownsEnvironment = false)
        }
        val disposable = Disposer.newDisposable("martin-analysis")
        return EnvironmentInfo(createEnvironment(disposable), disposable, ownsEnvironment = true)
    }

    private fun createEnvironment(disposable: org.jetbrains.kotlin.com.intellij.openapi.Disposable): KotlinCoreEnvironment {
        val configuration = CompilerConfiguration().apply {
            put(
                CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY,
                PrintingMessageCollector(System.err, MessageRenderer.PLAIN_RELATIVE_PATHS, false)
            )
            put(CommonConfigurationKeys.MODULE_NAME, projectDir.fileName.toString())
            put(JVMConfigurationKeys.JVM_TARGET, JvmTarget.JVM_21)
            addJvmClasspathRoots(classpathEntries)
        }

        return KotlinCoreEnvironment.createForProduction(
            disposable,
            configuration,
            EnvironmentConfigFiles.JVM_CONFIG_FILES
        )
    }
}
