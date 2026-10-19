package ai.victorious.victorfiles.core.apk

import java.io.File
import java.util.zip.ZipFile

/** One file in res/ or assets/ of an APK or extracted folder. */
data class ResEntry(val path: String, val size: Long) {
    val name: String get() = path.substringAfterLast('/')
    val kind: ResKind get() = ApkResources.kindOf(path)
}

enum class ResKind { XML, IMAGE, OTHER }

object ApkResources {
    private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")

    fun kindOf(path: String): ResKind = when (path.substringAfterLast('.', "").lowercase()) {
        "xml" -> ResKind.XML
        in IMAGE_EXT -> ResKind.IMAGE
        else -> ResKind.OTHER
    }

    fun isResPath(p: String) = p.startsWith("res/") || p.startsWith("assets/")

    /** Lists res/ and assets/ files (sorted by path). Works on .apk or an extracted folder. */
    fun list(target: File): List<ResEntry> = if (target.isDirectory) {
        listOf("res", "assets").flatMap { top ->
            File(target, top).walkTopDown().filter { it.isFile }
                .map { ResEntry(it.relativeTo(target).invariantSeparatorsPath, it.length()) }.toList()
        }.sortedBy { it.path }
    } else ZipFile(target).use { z ->
        z.entries().asSequence().filter { !it.isDirectory && isResPath(it.name) }
            .map { ResEntry(it.name, it.size) }.sortedBy { it.path }.toList()
    }

    /** Distinct first-level folders (res/layout, res/drawable-hdpi, assets/…) for grouping. */
    fun folders(entries: List<ResEntry>): List<String> =
        entries.map { it.path.substringBeforeLast('/', "") }.distinct().sorted()

    fun read(target: File, path: String): ByteArray = if (target.isDirectory) File(target, path).readBytes()
    else ZipFile(target).use { z -> z.getInputStream(z.getEntry(path) ?: error("Missing $path")).use { it.readBytes() } }

    fun readableXml(bytes: ByteArray, names: Map<Int, String> = emptyMap()): String =
        if (AxmlDecoder.isBinaryXml(bytes)) AxmlDecoder.toXml(bytes, names) else String(bytes, Charsets.UTF_8)

    /** Decodes XML and resolves @0x7f… refs using resources.arsc when present. */
    fun readableXml(target: File, path: String): String {
        val bytes = read(target, path)
        val names = runCatching { Arsc.loadFromTarget(target).names }.getOrDefault(emptyMap())
        return readableXml(bytes, names)
    }

    fun manifestXml(target: File): String = readableXml(target, "AndroidManifest.xml")
}
