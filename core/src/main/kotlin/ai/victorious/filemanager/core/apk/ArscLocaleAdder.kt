package ai.victorious.filemanager.core.apk

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Adds a brand-new language to a resources.arsc: a new `string` type chunk whose config is the
 * default config with language/region set (what `values-xx[-rYY]/strings.xml` compiles to).
 *
 * New texts are appended to the end of the global string pool, so every existing pool index
 * (and therefore every existing entry) stays valid. Strings left out simply fall back to the
 * default language at runtime.
 */
object ArscLocaleAdder {
    private const val CONFIG_LOCALE = 0x0004
    private const val FLAG_SPARSE = 0x01
    private const val FLAG_OFFSET16 = 0x02

    data class Locale(val language: String, val region: String?) {
        /** The arsc/values qualifier, e.g. `pt-rBR` or `eo`. */
        val qualifier: String get() = if (region == null) language else "$language-r$region"
    }

    /** Accepts `eo`, `pt-BR`, `pt_BR`, `pt-rBR` (2-letter language, optional 2-letter region). */
    fun parseLocale(raw: String): Locale {
        val t = raw.trim().replace('_', '-')
        val m = Regex("^([A-Za-z]{2})(?:-r?([A-Za-z]{2}))?$").matchEntire(t)
            ?: throw IllegalArgumentException("Use a 2-letter language code, optionally with a region, e.g. \"eo\" or \"pt-BR\"")
        return Locale(m.groupValues[1].lowercase(), m.groupValues[2].takeIf { it.isNotEmpty() }?.uppercase())
    }

    /** Common picks shown as chips; any 2-letter code can also be typed. */
    val SUGGESTED = listOf("es", "fr", "de", "it", "pt-BR", "ru", "tr", "ar", "hi", "id", "ja", "ko", "zh-CN", "uk", "pl", "vi", "eo")

    /**
     * Reads `name<TAB>text` or `name=text` lines (an exported default-language list filled in by a
     * person or an assistant) into resource id → text, matched against the default strings.
     * Blank lines, `#` comments and unknown names are skipped; `\n` becomes a newline.
     */
    fun textsFromLines(arsc: Arsc, lines: String): Map<Int, String> {
        val byName = arsc.strings.filter { it.locale == "default" }.associateBy { it.name }
        val out = LinkedHashMap<Int, String>()
        for (raw in lines.lineSequence()) {
            val line = raw.trimEnd()
            if (line.isBlank() || line.trimStart().startsWith("#")) continue
            val cut = line.indexOf('\t').takeIf { it > 0 } ?: line.indexOf('=').takeIf { it > 0 } ?: continue
            val name = line.substring(0, cut).trim().removePrefix("string/").removePrefix("@string/")
            val text = line.substring(cut + 1).trim().replace("\\n", "\n")
            val res = byName[name] ?: continue
            if (text.isNotEmpty()) out[res.resId] = text
        }
        return out
    }

    /** The default strings as `name<TAB>text` lines, ready to translate and feed back in. */
    fun templateLines(arsc: Arsc): String =
        arsc.strings.filter { it.locale == "default" }.joinToString("\n") { it.name + "\t" + it.value.replace("\n", "\\n") }

    /**
     * Returns new arsc bytes with [locale] added. [texts] maps resource id → translated text;
     * ids that are not default-config string resources are rejected.
     */
    fun addLocale(arscBytes: ByteArray, locale: String, texts: Map<Int, String>): ByteArray {
        val loc = parseLocale(locale)
        require(texts.isNotEmpty()) { "Nothing to translate" }
        val arsc = Arsc.open(arscBytes)
        require(loc.qualifier !in arsc.locales()) { "Language ${loc.qualifier} already exists — edit it instead" }
        val defaults = arsc.strings.filter { it.locale == "default" }.map { it.resId }.toSet()
        val unknown = texts.keys - defaults
        require(unknown.isEmpty()) { "Not default string resources: ${unknown.joinToString { "0x" + Integer.toHexString(it) }}" }

        // 1) Append the texts to the global pool.
        val buf = ByteBuffer.wrap(arscBytes).order(ByteOrder.LITTLE_ENDIAN)
        val tableHeader = buf.getShort(2).toInt() and 0xFFFF
        var poolStart = -1
        var pos = tableHeader
        while (pos + 8 <= arscBytes.size) {
            if ((buf.getShort(pos).toInt() and 0xFFFF) == Arsc.RES_STRING_POOL) { poolStart = pos; break }
            pos += buf.getInt(pos + 4).also { require(it >= 8) { "Corrupt chunk" } }
        }
        require(poolStart >= 0) { "No global string pool" }
        val poolSize = buf.getInt(poolStart + 4)
        val pool = StringPool.decode(arscBytes, poolStart)
        val indexOf = LinkedHashMap<Int, Int>()
        for ((id, text) in texts) { indexOf[id] = pool.strings.size; pool.strings += text }
        val newPool = pool.encode()

        // 2) Rewrite each package, adding the new type chunk where needed.
        val out = ByteArrayOutputStream(arscBytes.size + newPool.size)
        out.write(arscBytes, 0, poolStart)
        out.write(newPool)
        pos = poolStart + poolSize
        var added = 0
        while (pos + 8 <= arscBytes.size) {
            val type = buf.getShort(pos).toInt() and 0xFFFF
            val size = buf.getInt(pos + 4)
            require(size >= 8 && pos + size <= arscBytes.size) { "Corrupt chunk at $pos" }
            if (type == Arsc.RES_PACKAGE) {
                val (pkg, n) = rewritePackage(arscBytes, pos, size, loc, indexOf)
                out.write(pkg); added += n
            } else out.write(arscBytes, pos, size)
            pos += size
        }
        check(added == texts.size) { "Only $added of ${texts.size} strings were placed" }
        val bytes = out.toByteArray()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(4, bytes.size)
        return bytes
    }

    private fun rewritePackage(src: ByteArray, start: Int, size: Int, loc: Locale, indexOf: Map<Int, Int>): Pair<ByteArray, Int> {
        val buf = ByteBuffer.wrap(src).order(ByteOrder.LITTLE_ENDIAN)
        val headerSize = buf.getShort(start + 2).toInt() and 0xFFFF
        val pkgId = buf.getInt(start + 8)
        val typeStrings = StringPool.decode(src, start + buf.getInt(start + 12 + 256)).strings
        val stringTypeId = typeStrings.indexOf("string") + 1
        val mine = indexOf.filterKeys { (it ushr 24) == pkgId && ((it shr 16) and 0xFF) == stringTypeId }
        if (stringTypeId == 0 || mine.isEmpty()) return src.copyOfRange(start, start + size) to 0

        // Collect chunk boundaries.
        val chunks = ArrayList<IntArray>() // [pos, size, type, typeId]
        var pos = start + headerSize
        while (pos + 8 <= start + size) {
            val t = buf.getShort(pos).toInt() and 0xFFFF
            val cs = buf.getInt(pos + 4)
            require(cs >= 8 && pos + cs <= start + size) { "Corrupt package chunk" }
            val tid = if (t == Arsc.RES_TABLE_TYPE || t == Arsc.RES_TABLE_TYPE_SPEC) buf.get(pos + 8).toInt() and 0xFF else 0
            chunks += intArrayOf(pos, cs, t, tid)
            pos += cs
        }
        val default = chunks.firstOrNull { it[2] == Arsc.RES_TABLE_TYPE && it[3] == stringTypeId && isDefaultConfig(buf, it[0]) }
            ?: error("No default-language string table")
        val newType = buildType(src, default[0], loc, mine)
        val lastOfType = chunks.indexOfLast { it[3] == stringTypeId && (it[2] == Arsc.RES_TABLE_TYPE || it[2] == Arsc.RES_TABLE_TYPE_SPEC) }

        val out = ByteArrayOutputStream(size + newType.size)
        out.write(src, start, headerSize)
        for ((i, c) in chunks.withIndex()) {
            val bytes = src.copyOfRange(c[0], c[0] + c[1])
            if (c[2] == Arsc.RES_TABLE_TYPE_SPEC && c[3] == stringTypeId) markLocale(bytes, mine.keys.map { it and 0xFFFF })
            out.write(bytes)
            if (i == lastOfType) out.write(newType)
        }
        val tail = start + headerSize + chunks.sumOf { it[1] }
        if (tail < start + size) out.write(src, tail, start + size - tail)
        val bytes = out.toByteArray()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(4, bytes.size)
        return bytes to mine.size
    }

    private fun isDefaultConfig(buf: ByteBuffer, typeStart: Int): Boolean {
        val headerSize = buf.getShort(typeStart + 2).toInt() and 0xFFFF
        val cfg = typeStart + 20
        val cfgSize = buf.getInt(cfg)
        for (i in 4 until minOf(cfgSize, headerSize - 20)) if (buf.get(cfg + i).toInt() != 0) return false
        return true
    }

    private fun buildType(src: ByteArray, typeStart: Int, loc: Locale, texts: Map<Int, Int>): ByteArray {
        val buf = ByteBuffer.wrap(src).order(ByteOrder.LITTLE_ENDIAN)
        val headerSize = buf.getShort(typeStart + 2).toInt() and 0xFFFF
        val flags = buf.get(typeStart + 9).toInt() and 0xFF
        require(flags and (FLAG_SPARSE or FLAG_OFFSET16) == 0) { "This APK uses a compact resource table format that is not supported yet" }
        val entryCount = buf.getInt(typeStart + 12)
        val entriesStart = buf.getInt(typeStart + 16)
        val cfgSize = buf.getInt(typeStart + 20)
        require(headerSize >= 20 + 12) { "Unexpected resource config layout" }

        val byIndex = texts.mapKeys { it.key and 0xFFFF }
        val newEntriesStart = headerSize + entryCount * 4
        val total = newEntriesStart + byIndex.size * 16
        val o = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        o.put(src, typeStart, headerSize)
        o.putInt(4, total)
        o.put(9, 0)
        o.putInt(16, newEntriesStart)
        // Some hand-made tables leave the config size at 0; give it the room the header has.
        if (cfgSize < 12) o.putInt(20, headerSize - 20)
        // Language/region inside the copied default config.
        o.put(20 + 8, loc.language[0].code.toByte()); o.put(20 + 9, loc.language[1].code.toByte())
        loc.region?.let { o.put(20 + 10, it[0].code.toByte()); o.put(20 + 11, it[1].code.toByte()) }
        o.position(headerSize)
        var next = 0
        for (i in 0 until entryCount) {
            if (i in byIndex) { o.putInt(next * 16); next++ } else o.putInt(-1)
        }
        for (i in 0 until entryCount) {
            val poolIndex = byIndex[i] ?: continue
            val off = buf.getInt(typeStart + headerSize + i * 4)
            require(off != -1) { "Resource index $i has no default value" }
            val key = buf.getInt(typeStart + entriesStart + off + 4)
            o.putShort(8).putShort(0).putInt(key)
            o.putShort(8).put(0).put(0x03).putInt(poolIndex) // Res_value TYPE_STRING
        }
        check(o.position() == total)
        return o.array()
    }

    private fun markLocale(spec: ByteArray, indices: List<Int>) {
        val b = ByteBuffer.wrap(spec).order(ByteOrder.LITTLE_ENDIAN)
        val headerSize = b.getShort(2).toInt() and 0xFFFF
        val count = b.getInt(12)
        for (i in indices) if (i < count) {
            val p = headerSize + i * 4
            b.putInt(p, b.getInt(p) or CONFIG_LOCALE)
        }
    }
}
