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

    fun analyze(): AnalysisResult {
        val disposable = Disposer.newDisposable("martin-analysis")

        try {
            val configuration = CompilerConfiguration().apply {
                put(
                    CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY,
                    PrintingMessageCollector(System.err, MessageRenderer.PLAIN_RELATIVE_PATHS, false)
                )
                put(CommonConfigurationKeys.MODULE_NAME, projectDir.fileName.toString())
                put(JVMConfigurationKeys.JVM_TARGET, JvmTarget.JVM_21)
                addJvmClasspathRoots(classpathEntries)
            }

            val environment = KotlinCoreEnvironment.createForProduction(
                disposable,
                configuration,
                EnvironmentConfigFiles.JVM_CONFIG_FILES
            )

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
            )
        } catch (e: Exception) {
            Disposer.dispose(disposable)
            throw e
        }
    }
}
