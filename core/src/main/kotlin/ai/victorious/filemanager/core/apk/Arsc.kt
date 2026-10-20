package ai.victorious.filemanager.core.apk

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A ResStringPool decoded for editing; style spans are carried through as raw bytes. */
class StringPool(
    val strings: MutableList<String>,
    val utf8: Boolean,
    val flags: Int,
    val styleCount: Int,
    private val styleOffsets: IntArray,
    private val styleData: ByteArray,
) {
    fun encode(): ByteArray {
        val data = ByteArrayOutputStream()
        val offsets = IntArray(strings.size)
        for ((i, s) in strings.withIndex()) {
            offsets[i] = data.size()
            if (utf8) {
                val bytes = s.toByteArray(Charsets.UTF_8)
                writeLen8(data, s.length); writeLen8(data, bytes.size)
                data.write(bytes); data.write(0)
            } else {
                writeLen16(data, s.length)
                data.write(s.toByteArray(Charsets.UTF_16LE)); data.write(0); data.write(0)
            }
        }
        while (data.size() % 4 != 0) data.write(0)
        val header = 28
        val stringsStart = header + strings.size * 4 + styleCount * 4
        val stylesStart = if (styleCount > 0) stringsStart + data.size() else 0
        val total = stringsStart + data.size() + (if (styleCount > 0) styleData.size else 0)
        val b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        b.putShort(Arsc.RES_STRING_POOL.toShort()).putShort(header.toShort()).putInt(total)
            .putInt(strings.size).putInt(styleCount).putInt(flags).putInt(stringsStart).putInt(stylesStart)
        offsets.forEach { b.putInt(it) }
        styleOffsets.forEach { b.putInt(it) }
        b.put(data.toByteArray())
        if (styleCount > 0) b.put(styleData)
        return b.array()
    }

    private fun writeLen8(o: ByteArrayOutputStream, n: Int) {
        require(n <= 0x7FFF) { "String too long" }
        if (n > 0x7F) { o.write(0x80 or (n shr 8)); o.write(n and 0xFF) } else o.write(n)
    }

    private fun writeLen16(o: ByteArrayOutputStream, n: Int) {
        if (n > 0x7FFF) {
            val hi = 0x8000 or (n shr 16); o.write(hi and 0xFF); o.write(hi shr 8)
            o.write(n and 0xFF); o.write((n shr 8) and 0xFF)
        } else { o.write(n and 0xFF); o.write(n shr 8) }
    }

    companion object {
        const val UTF8_FLAG = 0x100

        fun decode(bytes: ByteArray, start: Int): StringPool {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val headerSize = buf.getShort(start + 2).toInt() and 0xFFFF
            val size = buf.getInt(start + 4)
            val count = buf.getInt(start + 8)
            val styles = buf.getInt(start + 12)
            val flags = buf.getInt(start + 16)
            val stringsStart = start + buf.getInt(start + 20)
            val stylesStartRel = buf.getInt(start + 24)
            val utf8 = flags and UTF8_FLAG != 0
            val list = ArrayList<String>(count)
            for (i in 0 until count) {
                var p = stringsStart + buf.getInt(start + headerSize + i * 4)
                list += if (utf8) {
                    p += if (buf.get(p).toInt() and 0x80 != 0) 2 else 1
                    var len = buf.get(p).toInt() and 0xFF
                    if (len and 0x80 != 0) {
                        len = ((len and 0x7F) shl 8) or (buf.get(p + 1).toInt() and 0xFF); p += 2
                    } else p += 1
                    String(bytes, p, len, Charsets.UTF_8)
                } else {
                    var len = buf.getShort(p).toInt() and 0xFFFF
                    if (len and 0x8000 != 0) {
                        len = ((len and 0x7FFF) shl 16) or (buf.getShort(p + 2).toInt() and 0xFFFF); p += 4
                    } else p += 2
                    String(bytes, p, len * 2, Charsets.UTF_16LE)
                }
            }
            val styleOffsets = IntArray(styles) { buf.getInt(start + headerSize + count * 4 + it * 4) }
            val styleData = if (styles > 0 && stylesStartRel > 0) {
                bytes.copyOfRange(start + stylesStartRel, start + size)
            } else ByteArray(0)
            // Drop SORTED flag: edits break lexicographic order.
            return StringPool(list, utf8, flags and 0x1.inv(), styles, styleOffsets, styleData)
        }
    }
}

/** A string-typed resource value in one configuration. */
data class ArscString(
    val resId: Int,
    val name: String,
    val locale: String,
    val poolIndex: Int,
    val value: String,
    val styled: Boolean,
)

/**
 * Minimal resources.arsc reader/writer: resolves resource IDs to `type/name`, lists string
 * values per locale, and rewrites the global string pool after edits. Everything after the
 * global pool (packages, types, entries) is copied byte-for-byte, so entry offsets into the
 * pool stay valid as long as we only rewrite string contents (pool indices unchanged).
 */
class Arsc private constructor(
    private val original: ByteArray,
    private val poolStart: Int,
    private val poolSize: Int,
    val pool: StringPool,
) {
    /** resId -> "type/name" (from the first package that defines it). */
    val names: Map<Int, String>
    val strings: List<ArscString>
    val packageNames: List<String>

    init {
        val buf = ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN)
        val names = LinkedHashMap<Int, String>()
        val strs = ArrayList<ArscString>()
        val pkgs = ArrayList<String>()
        var pos = buf.getShort(2).toInt() and 0xFFFF
        while (pos + 8 <= original.size) {
            val type = buf.getShort(pos).toInt() and 0xFFFF
            val size = buf.getInt(pos + 4)
            if (size < 8 || pos + size > original.size) break
            if (type == RES_PACKAGE) parsePackage(buf, pos, size, names, strs, pkgs)
            pos += size
        }
        this.names = names
        this.strings = strs
        this.packageNames = pkgs
    }

    private fun parsePackage(
        buf: ByteBuffer,
        start: Int,
        size: Int,
        names: MutableMap<Int, String>,
        out: MutableList<ArscString>,
        pkgs: MutableList<String>,
    ) {
        val headerSize = buf.getShort(start + 2).toInt() and 0xFFFF
        val pkgId = buf.getInt(start + 8)
        val nameChars = CharArray(128) { buf.getShort(start + 12 + it * 2).toInt().toChar() }
        pkgs += String(nameChars).substringBefore('\u0000')
        val typeStrings = StringPool.decode(original, start + buf.getInt(start + 12 + 256)).strings
        val keyStrings = StringPool.decode(original, start + buf.getInt(start + 12 + 256 + 8)).strings
        var pos = start + headerSize
        val end = start + size
        var typeId = 0
        while (pos + 8 <= end) {
            val type = buf.getShort(pos).toInt() and 0xFFFF
            val csize = buf.getInt(pos + 4)
            if (csize < 8 || pos + csize > end) break
            when (type) {
                RES_TABLE_TYPE_SPEC -> typeId = buf.get(pos + 8).toInt() and 0xFF
                RES_TABLE_TYPE -> parseType(buf, pos, csize, pkgId, typeId, typeStrings, keyStrings, names, out)
            }
            pos += csize
        }
    }

    private fun parseType(
        buf: ByteBuffer,
        start: Int,
        size: Int,
        pkgId: Int,
        typeId: Int,
        typeStrings: List<String>,
        keyStrings: List<String>,
        names: MutableMap<Int, String>,
        out: MutableList<ArscString>,
    ) {
        val headerSize = buf.getShort(start + 2).toInt() and 0xFFFF
        val tid = buf.get(start + 8).toInt() and 0xFF
        val entryCount = buf.getInt(start + 12)
        val entriesStart = buf.getInt(start + 16)
        val config = decodeConfig(buf, start + 20, headerSize - 20)
        val typeName = typeStrings.getOrNull(tid - 1) ?: "type$tid"
        val isStringType = typeName == "string"
        for (i in 0 until entryCount) {
            val off = buf.getInt(start + headerSize + i * 4)
            if (off == -1) continue
            val entry = start + entriesStart + off
            if (entry + 8 > start + size) continue
            val flags = buf.getShort(entry + 2).toInt() and 0xFFFF
            val key = buf.getInt(entry + 4)
            val entryName = keyStrings.getOrNull(key) ?: "entry$i"
            val resId = (pkgId shl 24) or (tid shl 16) or i
            names.putIfAbsent(resId, "$typeName/$entryName")
            if (!isStringType || flags and 0x01 != 0) continue // skip maps/complex
            if (entry + 16 > start + size) continue
            val dataType = buf.get(entry + 11).toInt() and 0xFF
            val data = buf.getInt(entry + 12)
            if (dataType == 0x03) { // TYPE_STRING
                val value = pool.strings.getOrNull(data) ?: continue
                out += ArscString(resId, entryName, config, data, value, styled = false)
            }
        }
    }

    private fun decodeConfig(buf: ByteBuffer, start: Int, size: Int): String {
        if (size < 8) return "default"
        val mcc = buf.getShort(start).toInt() and 0xFFFF
        val mnc = buf.getShort(start + 2).toInt() and 0xFFFF
        val lang = charPair(buf.get(start + 4), buf.get(start + 5))
        val country = charPair(buf.get(start + 6), buf.get(start + 7))
        val parts = mutableListOf<String>()
        if (mcc != 0) parts += "mcc$mcc"
        if (mnc != 0) parts += "mnc$mnc"
        if (lang.isNotEmpty()) parts += if (country.isNotEmpty()) "$lang-r$country" else lang
        return parts.joinToString("-").ifEmpty { "default" }
    }

    private fun charPair(a: Byte, b: Byte): String {
        val c1 = (a.toInt() and 0xFF).toChar()
        val c2 = (b.toInt() and 0xFF).toChar()
        return buildString {
            if (c1 != '\u0000') append(c1)
            if (c2 != '\u0000') append(c2)
        }
    }

    fun locales(): List<String> = strings.map { it.locale }.distinct().sortedWith(compareBy({ it != "default" }, { it }))

    fun search(query: String, locale: String? = null): List<ArscString> {
        val q = query.lowercase()
        return strings.filter {
            (locale == null || it.locale == locale) &&
                (q.isEmpty() || it.name.lowercase().contains(q) || it.value.lowercase().contains(q) ||
                    ("0x" + Integer.toHexString(it.resId)).contains(q))
        }
    }

    /** Resolves `@0x7f0…` / `0x7f0…` to `@type/name`, or returns the original. */
    fun resolveRef(ref: String): String {
        val hex = ref.removePrefix("@").removePrefix("0x").removePrefix("0X")
        val id = hex.toLongOrNull(16)?.toInt() ?: return ref
        val name = names[id] ?: return ref
        return "@$name"
    }

    /**
     * Applies [edits] (poolIndex -> new text). Indices must already exist; adding new strings
     * would invalidate entry data offsets, so that is not supported.
     */
    fun withEdits(edits: Map<Int, String>): ByteArray {
        val p2 = StringPool.decode(original, poolStart)
        for ((i, v) in edits) {
            require(i in p2.strings.indices) { "Unknown pool index $i" }
            p2.strings[i] = v
        }
        val newPool = p2.encode()
        val out = ByteArrayOutputStream(original.size - poolSize + newPool.size)
        out.write(original, 0, poolStart)
        out.write(newPool)
        out.write(original, poolStart + poolSize, original.size - (poolStart + poolSize))
        val bytes = out.toByteArray()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(4, bytes.size)
        return bytes
    }

    companion object {
        const val RES_TABLE = 0x0002
        const val RES_STRING_POOL = 0x0001
        const val RES_PACKAGE = 0x0200
        const val RES_TABLE_TYPE = 0x0201
        const val RES_TABLE_TYPE_SPEC = 0x0202

        fun open(bytes: ByteArray): Arsc {
            require(bytes.size >= 12 && (bytes[0].toInt() and 0xFF) == RES_TABLE) { "Not a resources.arsc" }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var pos = buf.getShort(2).toInt() and 0xFFFF
            while (pos + 8 <= bytes.size) {
                val type = buf.getShort(pos).toInt() and 0xFFFF
                val size = buf.getInt(pos + 4)
                if (type == RES_STRING_POOL) {
                    val pool = StringPool.decode(bytes, pos)
                    return Arsc(bytes, pos, size, pool)
                }
                if (size < 8) break
                pos += size
            }
            throw IllegalArgumentException("No global string pool in resources.arsc")
        }

        fun open(file: java.io.File): Arsc = open(file.readBytes())

        fun loadFromTarget(target: java.io.File): Arsc = open(ApkResources.read(target, "resources.arsc"))
    }
}
