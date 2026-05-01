package martin.rewriter

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Represents a single text replacement in a source file.
 */
data class TextEdit(
    val filePath: Path,
    val offset: Int,
    val length: Int,
    val replacement: String,
)

/**
 * Applies text edits to source files on disk.
 *
 * Edits must be sorted in reverse offset order per file so that earlier edits
 * don't invalidate the offsets of later edits.
 */
object SourceRewriter {

    fun applyEdits(edits: List<TextEdit>): Int {
        val editsByFile = edits.groupBy { it.filePath }

        for ((filePath, fileEdits) in editsByFile) {
            var content = filePath.readText()

            val sorted = fileEdits.sortedByDescending { it.offset }
            for (edit in sorted) {
                content = content.substring(0, edit.offset) +
                    edit.replacement +
                    content.substring(edit.offset + edit.length)
            }

            filePath.writeText(content)
        }

        return editsByFile.size
    }
}
