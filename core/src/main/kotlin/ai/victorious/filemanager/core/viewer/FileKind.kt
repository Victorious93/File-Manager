package ai.victorious.filemanager.core.viewer

import java.io.File

enum class FileKind { TEXT, IMAGE, AUDIO, VIDEO, ARCHIVE, BINARY }

/** Picks a default viewer for a file from its extension, falling back to content sniffing. */
object FileKinds {
    private val text = setOf(
        "txt", "md", "markdown", "log", "csv", "tsv", "json", "xml", "html", "htm", "css", "js", "ts",
        "kt", "kts", "java", "py", "sh", "bash", "c", "h", "cpp", "hpp", "rs", "go", "rb", "php", "yml",
        "yaml", "toml", "ini", "cfg", "conf", "properties", "gradle", "sql", "srt", "gitignore", "env",
    )
    private val image = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "ico")
    private val audio = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "flac", "wav", "amr", "mid", "midi")
    private val video = setOf("mp4", "m4v", "mkv", "webm", "3gp", "mov", "avi", "ts")
    val archiveExtensions = setOf("zip", "jar", "apk", "7z", "tar", "gz", "tgz", "rar")

    fun extension(name: String): String {
        val lower = name.lowercase()
        if (lower.endsWith(".tar.gz")) return "tgz"
        return lower.substringAfterLast('.', "")
    }

    fun byName(name: String): FileKind? = when (extension(name)) {
        in image -> FileKind.IMAGE
        in audio -> FileKind.AUDIO
        in video -> if (extension(name) == "ts") null else FileKind.VIDEO
        in archiveExtensions -> FileKind.ARCHIVE
        in text -> FileKind.TEXT
        else -> null
    }

    fun detect(file: File): FileKind {
        byName(file.name)?.let { return it }
        val head = runCatching { file.inputStream().use { s -> ByteArray(4096).let { b -> b.copyOf(s.read(b).coerceAtLeast(0)) } } }
            .getOrDefault(ByteArray(0))
        return if (looksLikeText(head)) FileKind.TEXT else FileKind.BINARY
    }

    /** Heuristic: no NUL bytes, valid-ish UTF-8, few control characters. Empty files count as text. */
    fun looksLikeText(sample: ByteArray): Boolean {
        if (sample.isEmpty()) return true
        var control = 0
        for (b in sample) {
            val v = b.toInt() and 0xFF
            if (v == 0) return false
            if (v < 0x09 || (v in 0x0E..0x1F)) control++
        }
        if (control * 100 / sample.size > 5) return false
        return Utf8.isValid(sample, allowTruncatedTail = true)
    }
}

object Utf8 {
    fun isValid(bytes: ByteArray, allowTruncatedTail: Boolean = false): Boolean {
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            val extra = when {
                b < 0x80 -> 0
                b in 0xC2..0xDF -> 1
                b in 0xE0..0xEF -> 2
                b in 0xF0..0xF4 -> 3
                else -> return false
            }
            if (i + extra >= bytes.size && extra > 0) return allowTruncatedTail
            for (k in 1..extra) {
                if ((bytes[i + k].toInt() and 0xC0) != 0x80) return false
            }
            i += extra + 1
        }
        return true
    }
}
