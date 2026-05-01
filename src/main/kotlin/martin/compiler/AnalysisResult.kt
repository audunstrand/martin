package martin.compiler

import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.BindingContext

class AnalysisResult(
    val files: List<KtFile>,
    val bindingContext: BindingContext,
    val environment: KotlinCoreEnvironment,
    private val disposable: Disposable,
    private val ownsDisposable: Boolean = true,
) : AutoCloseable {
    override fun close() {
        if (ownsDisposable) {
            Disposer.dispose(disposable)
        }
    }
}
