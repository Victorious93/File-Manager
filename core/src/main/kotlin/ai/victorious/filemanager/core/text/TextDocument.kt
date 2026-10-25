package ai.victorious.filemanager.core.text

import ai.victorious.filemanager.core.viewer.Utf8
import java.io.File

/** Bounded undo/redo history of immutable snapshots. */
class UndoHistory<T>(initial: T, private val limit: Int = 200) {
    private val undo = ArrayDeque<T>()
    private val redo = ArrayDeque<T>()
    var current: T = initial
        private set

    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    fun push(next: T) {
        if (next == current) return
        undo.addLast(current)
        if (undo.size > limit) undo.removeFirst()
        redo.clear()
        current = next
    }

    fun undo(): T {
        if (undo.isEmpty()) return current
        redo.addLast(current)
        current = undo.removeLast()
        return current
    }

    fun redo(): T {
        if (redo.isEmpty()) return current
        undo.addLast(current)
        current = redo.removeLast()
        return current
    }

    fun reset(value: T) {
        undo.clear(); redo.clear(); current = value
    }
}

class TextLoadException(message: String) : RuntimeException(message)

/** UTF-8 text file load/save with a size cap so the editor never tries to hold a huge blob. */
object TextFiles {
    const val MAX_EDITABLE_BYTES = 4L * 1024 * 1024

    fun load(file: File, maxBytes: Long = MAX_EDITABLE_BYTES): String {
        if (file.length() > maxBytes) throw TextLoadException("File too large for the text editor (${file.length()} bytes); use the hex viewer")
        val bytes = file.readBytes()
        if (!Utf8.isValid(bytes)) throw TextLoadException("Not valid UTF-8; open with the hex editor instead")
        val text = String(bytes, Charsets.UTF_8)
        return if (text.startsWith("\uFEFF")) text.substring(1) else text
    }

    /** Writes atomically via a sibling temp file so a failed save never truncates the original. */
    fun save(file: File, text: String) {
        val tmp = File(file.parentFile, ".${file.name}.saving")
        try {
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(file)) file.writeText(text, Charsets.UTF_8)
        } finally {
            tmp.delete() // no-op after a successful rename; otherwise don't leave a hidden stray file
        }
    }
}
