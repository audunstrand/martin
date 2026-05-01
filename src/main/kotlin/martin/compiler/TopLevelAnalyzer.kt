package martin.compiler

import org.jetbrains.kotlin.cli.jvm.compiler.CliBindingTrace
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.TopDownAnalyzerFacadeForJVM
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.BindingContext

/**
 * Performs top-level analysis (name resolution, type checking) on a set of KtFiles.
 */
class TopLevelAnalyzer(
    private val environment: KotlinCoreEnvironment,
) {
    fun analyzeFiles(files: List<KtFile>): BindingContext {
        val result = TopDownAnalyzerFacadeForJVM.analyzeFilesWithJavaIntegration(
            environment.project,
            files,
            CliBindingTrace(environment.project),
            environment.configuration,
            environment::createPackagePartProvider,
        )
        return result.bindingContext
    }
}
