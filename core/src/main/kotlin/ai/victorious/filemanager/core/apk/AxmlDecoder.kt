package ai.victorious.filemanager.core.apk

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Converts Android binary XML (AndroidManifest.xml, res/layout XML, …) into
 * readable, indented XML text. Resource references are shown as `@0x7f…`
 * because resolving them needs resources.arsc (deferred).
 */
object AxmlDecoder {
    private const val RES_STRING_POOL = 0x0001
    private const val RES_XML = 0x0003
    private const val RES_XML_RESOURCE_MAP = 0x0180
    private const val START_NS = 0x0100
    private const val START_ELEMENT = 0x0102
    private const val END_ELEMENT = 0x0103
    private const val CDATA = 0x0104

    fun isBinaryXml(bytes: ByteArray): Boolean =
        bytes.size >= 8 && (bytes[0].toInt() and 0xFF) == RES_XML && bytes[1].toInt() == 0

    fun toXml(bytes: ByteArray, names: Map<Int, String> = emptyMap()): String {
        require(isBinaryXml(bytes)) { "Not a binary XML file" }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var strings: List<String> = emptyList()
        var resIds = IntArray(0)
        val pendingNs = mutableListOf<Pair<String, String>>()
        val nsPrefix = mutableMapOf<String, String>()
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        var depth = 0
        fun str(i: Int) = if (i < 0) null else strings.getOrNull(i)
        fun indent() = "    ".repeat(depth)
        var pos = buf.getShort(2).toInt() and 0xFFFF
        while (pos + 8 <= bytes.size) {
            val type = buf.getShort(pos).toInt() and 0xFFFF
            val headerSize = buf.getShort(pos + 2).toInt() and 0xFFFF
            val size = buf.getInt(pos + 4)
            if (size < 8 || pos + size > bytes.size) break
            val ext = pos + headerSize
            when (type) {
                RES_STRING_POOL -> strings = AxmlParser.readStringPool(buf, pos)
                RES_XML_RESOURCE_MAP -> resIds = IntArray((size - headerSize) / 4) { buf.getInt(pos + headerSize + it * 4) }
                START_NS -> {
                    val prefix = str(buf.getInt(ext)) ?: ""
                    val uri = str(buf.getInt(ext + 4)) ?: ""
                    nsPrefix[uri] = prefix
                    pendingNs += prefix to uri
                }
                START_ELEMENT -> {
                    val name = str(buf.getInt(ext + 4)) ?: "?"
                    val attrStart = buf.getShort(ext + 8).toInt() and 0xFFFF
                    val attrSize = buf.getShort(ext + 10).toInt() and 0xFFFF
                    val attrCount = buf.getShort(ext + 12).toInt() and 0xFFFF
                    sb.append(indent()).append('<').append(name)
                    val attrIndent = "\n" + indent() + "    "
                    pendingNs.forEach { (p, u) -> sb.append(attrIndent).append("xmlns:").append(p).append("=\"").append(escape(u)).append('"') }
                    pendingNs.clear()
                    for (i in 0 until attrCount) {
                        val a = ext + attrStart + i * attrSize
                        val ns = str(buf.getInt(a))
                        val nameIdx = buf.getInt(a + 4)
                        var attrName = str(nameIdx).orEmpty()
                        if (attrName.isEmpty()) attrName = resIds.getOrNull(nameIdx)?.let { "attr_0x" + Integer.toHexString(it) } ?: "attr$i"
                        val prefix = ns?.let { nsPrefix[it] ?: "ns" }
                        val value = formatValue(str(buf.getInt(a + 8)), bytes[a + 15].toInt() and 0xFF, buf.getInt(a + 16), strings, names)
                        sb.append(attrIndent)
                        if (prefix != null) sb.append(prefix).append(':')
                        sb.append(attrName).append("=\"").append(escape(value)).append('"')
                    }
                    sb.append(">\n")
                    depth++
                }
                END_ELEMENT -> {
                    depth = maxOf(0, depth - 1)
                    sb.append(indent()).append("</").append(str(buf.getInt(ext + 4)) ?: "?").append(">\n")
                }
                CDATA -> str(buf.getInt(ext))?.takeIf { it.isNotBlank() }?.let { sb.append(indent()).append(escape(it.trim())).append('\n') }
            }
            pos += size
        }
        return sb.toString()
    }

    private val DIM_UNITS = arrayOf("px", "dp", "sp", "pt", "in", "mm")

    /** Formats a Res_value. [raw] wins when present (string attributes). */
    fun formatValue(raw: String?, dataType: Int, data: Int, strings: List<String> = emptyList(), names: Map<Int, String> = emptyMap()): String {
        if (raw != null) return raw
        return when (dataType) {
            0x00 -> ""
            0x01 -> if (data == 0) "@null" else names[data]?.let { "@$it" } ?: ("@0x" + hex8(data))
            0x02 -> names[data]?.let { "?$it" } ?: ("?0x" + hex8(data))
            0x03 -> strings.getOrNull(data) ?: ""
            0x04 -> java.lang.Float.intBitsToFloat(data).toString()
            0x05 -> trimFloat(complex(data)) + DIM_UNITS.getOrElse(data and 0xF) { "?" }
            0x06 -> trimFloat(complex(data) * 100) + (if (data and 0xF == 0) "%" else "%p")
            0x10 -> data.toString()
            0x11 -> "0x" + hex8(data)
            0x12 -> (data != 0).toString()
            in 0x1c..0x1f -> "#" + hex8(data)
            else -> "0x" + hex8(data)
        }
    }

    private fun complex(data: Int): Float {
        val mantissa = (data shr 8).toFloat() // signed 24-bit
        return when ((data shr 4) and 0x3) {
            0 -> mantissa
            1 -> mantissa / (1 shl 7)
            2 -> mantissa / (1 shl 15)
            else -> mantissa / (1 shl 23)
        }
    }

    private fun trimFloat(f: Float): String = if (f == f.toLong().toFloat()) f.toLong().toString() else f.toString()
    private fun hex8(v: Int) = String.format("%08x", v)
    fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
