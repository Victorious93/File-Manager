package ai.victorious.victorfiles.core.ops

import java.io.File
import java.io.IOException

/**
 * Split a file into numbered parts (`name.001`, `name.002`, …) and join them back.
 * Plain byte slices, no header, so parts can also be rejoined with `cat name.0* > name`.
 */
object FileSplit {
    const val MAX_PARTS = 999
    private const val BUF = 64 * 1024
    private val PART_RE = Regex("""^(.+)\.(\d{3})$""")

    /**
     * Parses a part size such as "50", "50mb", "1.5 GB", "700k" (bare numbers are MB).
     * Returns null for blank, non-positive, or unparseable input. Units are binary (1 MB = 1024*1024).
     */
    fun parseSize(text: String): Long? {
        val m = Regex("""^\s*(\d+(?:\.\d+)?)\s*([kmg]?)b?\s*$""", RegexOption.IGNORE_CASE).matchEntire(text) ?: return null
        val n = m.groupValues[1].toDoubleOrNull() ?: return null
        val mult = when (m.groupValues[2].lowercase()) { "k" -> 1024.0; "g" -> 1024.0 * 1024 * 1024; else -> 1024.0 * 1024 }
        val bytes = n * mult
        return if (bytes >= 1 && bytes < Long.MAX_VALUE) bytes.toLong() else null
    }

    /** Splits [source] into [outDir]; returns the parts in order. Refuses to overwrite existing parts. */
    fun split(source: File, partSize: Long, outDir: File = source.parentFile ?: File(".")): List<File> {
        require(source.isFile) { "Not a file: ${source.name}" }
        require(partSize > 0) { "Part size must be positive" }
        val length = source.length()
        require(length > 0) { "File is empty" }
        val count = (length + partSize - 1) / partSize
        require(count <= MAX_PARTS) { "Would create $count parts (max $MAX_PARTS); use a larger part size" }
        require(count > 1) { "Part size is not smaller than the file; nothing to split" }
        outDir.mkdirs()

        val parts = (1..count.toInt()).map { File(outDir, "%s.%03d".format(source.name, it)) }
        parts.firstOrNull { it.exists() }?.let { throw IOException("Already exists: ${it.name}") }

        val created = ArrayList<File>()
        try {
            source.inputStream().buffered(BUF).use { input ->
                val buf = ByteArray(BUF)
                for (part in parts) {
                    created.add(part)
                    part.outputStream().buffered(BUF).use { out ->
                        var remaining = minOf(partSize, length - (created.size - 1) * partSize)
                        while (remaining > 0) {
                            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                            if (n < 0) throw IOException("Source ended early (file changed while splitting?)")
                            out.write(buf, 0, n)
                            remaining -= n
                        }
                    }
                }
            }
        } catch (e: Exception) {
            created.forEach { it.delete() } // only the parts this call created
            throw e
        }
        return parts
    }

    /** Parts that [first] (any numbered part) belongs to, in order; must be contiguous from .001. */
    fun findParts(first: File): List<File> {
        val m = PART_RE.matchEntire(first.name) ?: throw IllegalArgumentException("Not a numbered part: ${first.name}")
        val base = m.groupValues[1]
        val dir = first.parentFile ?: File(".")
        val parts = ArrayList<File>()
        var i = 1
        while (i <= MAX_PARTS) {
            val f = File(dir, "%s.%03d".format(base, i))
            if (!f.isFile) break
            parts.add(f); i++
        }
        if (parts.isEmpty()) throw IOException("Part .001 not found for $base")
        // A gap (e.g. .001, .002, .004) would silently produce a truncated join; refuse instead.
        dir.listFiles()?.forEach { f ->
            val n = PART_RE.matchEntire(f.name)?.takeIf { it.groupValues[1] == base }?.groupValues?.get(2)?.toInt()
            if (n != null && n > parts.size) throw IOException("Missing part %03d for %s".format(parts.size + 1, base))
        }
        val number = m.groupValues[2].toInt()
        if (number > parts.size) throw IOException("Missing part %03d for %s".format(parts.size + 1, base))
        return parts
    }

    /** Joins the parts of [anyPart] into [output] (default: base name beside the parts). Never overwrites. */
    fun join(anyPart: File, output: File? = null): File {
        val parts = findParts(anyPart)
        val base = PART_RE.matchEntire(anyPart.name)!!.groupValues[1]
        val dest = output ?: File(anyPart.parentFile ?: File("."), base)
        if (dest.exists()) throw IOException("Already exists: ${dest.name}")
        val tmp = File(dest.parentFile ?: File("."), ".${dest.name}.joining")
        try {
            tmp.outputStream().buffered(BUF).use { out ->
                for (p in parts) p.inputStream().buffered(BUF).use { it.copyTo(out, BUF) }
            }
            if (!tmp.renameTo(dest)) throw IOException("Could not move joined file into place")
        } finally {
            tmp.delete()
        }
        return dest
    }
}
