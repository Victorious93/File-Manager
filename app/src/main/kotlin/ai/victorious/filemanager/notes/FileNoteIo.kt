package ai.victorious.filemanager.notes

import ai.victorious.filemanager.core.notes.NoteIo
import java.io.File

class FileNoteIo : NoteIo {
    override fun listMarkdown(root: String): List<String> {
        val dir = File(root)
        if (!dir.isDirectory) return emptyList()
        return dir.walkTopDown().filter { it.isFile && it.extension.equals("md", true) }.map { it.absolutePath }.sorted().toList()
    }
    override fun read(path: String): String = File(path).readText()
    override fun write(path: String, text: String) {
        File(path).parentFile?.mkdirs()
        File(path).writeText(text)
    }
    override fun exists(path: String): Boolean = File(path).exists()
    override fun mkdir(path: String) { File(path).mkdirs() }
}
