package ai.victorious.filemanager.core.apk

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Structural editor for Android binary XML (AXML), e.g. AndroidManifest.xml inside an APK.
 *
 * It parses the chunk stream (string pool, resource-id map, namespace / element / text nodes)
 * into an editable model and re-encodes a fresh, byte-valid AXML. Nothing is compiled from
 * text, so resource IDs, value types and unrelated attributes survive unchanged.
 *
 * String edits only ever *append* to the pool. Existing indices (used by every node) stay
 * valid, which is what keeps the re-encode safe.
 */
class AxmlEditor private constructor(
    private val pool: StringPool,
    private var resIds: IntArray,
    private val nodes: MutableList<Node>,
) {
    /** One attribute (20 bytes on disk). Indices are into [pool]; -1 = none. */
    class Attr(var ns: Int, var name: Int, var raw: Int, var type: Int, var data: Int)

    sealed class Node
    /** A START_ELEMENT, fully decoded so attributes can be added/removed. */
    class Element(
        var line: Int, var comment: Int, var ns: Int, var name: Int,
        var idIndex: Int, var classIndex: Int, var styleIndex: Int,
        val attrs: MutableList<Attr>,
    ) : Node()
    /** Any other chunk (namespaces, END_ELEMENT, CDATA), kept byte-for-byte. */
    class Raw(val type: Int, val bytes: ByteArray) : Node()

    fun string(i: Int): String? = if (i < 0) null else pool.strings.getOrNull(i)

    /** Pool index of [s], appending it when absent (never reorders existing strings). */
    fun intern(s: String): Int {
        val i = pool.strings.indexOf(s)
        if (i >= 0) return i
        pool.strings += s
        return pool.strings.lastIndex
    }

    /** Resource id of the attribute name at pool index [nameIdx] (0 when none). */
    fun resIdOf(nameIdx: Int): Int = if (nameIdx in resIds.indices) resIds[nameIdx] else 0

    val elements: List<Element> get() = nodes.filterIsInstance<Element>()

    fun elementName(e: Element): String = string(e.name).orEmpty()

    /** Elements named [name] in document order. */
    fun elements(name: String): List<Element> = elements.filter { elementName(it) == name }

    fun attr(e: Element, resId: Int, plainName: String? = null): Attr? = e.attrs.firstOrNull {
        (resId != 0 && resIdOf(it.name) == resId) || (plainName != null && it.ns < 0 && string(it.name) == plainName)
    }

    /** Readable value of an attribute (string attributes return their text). */
    fun valueOf(a: Attr): String = string(a.raw) ?: AxmlDecoder.formatValue(null, a.type, a.data, pool.strings)

    private val androidNs: Int
        get() = pool.strings.indexOf(ANDROID_NS).also { require(it >= 0) { "Manifest has no android namespace" } }

    /**
     * Pool index of the attribute-name string for framework attr [resId]. If the manifest
     * never used that attribute, its name is inserted at the end of the resource-mapped part
     * of the pool (AXML requires mapped names at indices 0..map.size-1), and every index in
     * every node is shifted to match.
     */
    private fun attrNameIndex(resId: Int): Int {
        val i = resIds.indexOf(resId)
        if (i >= 0) return i
        val name = FRAMEWORK_ATTRS[resId]
            ?: throw IllegalArgumentException("Unknown framework attribute 0x${Integer.toHexString(resId)}")
        val k = resIds.size
        require(pool.styleCount <= k) { "Styled AXML string pools are not supported" }
        pool.strings.add(k, name)
        resIds = resIds + resId
        shiftIndices(k)
        return k
    }

    private fun shiftIndices(k: Int) {
        fun sh(i: Int) = if (i >= k) i + 1 else i
        for (n in nodes) when (n) {
            is Element -> {
                n.comment = sh(n.comment); n.ns = sh(n.ns); n.name = sh(n.name)
                for (a in n.attrs) {
                    a.ns = sh(a.ns); a.name = sh(a.name); a.raw = sh(a.raw)
                    if (a.type == TYPE_STRING) a.data = sh(a.data)
                }
            }
            is Raw -> {
                val b = ByteBuffer.wrap(n.bytes).order(ByteOrder.LITTLE_ENDIAN)
                fun fix(off: Int) { if (off + 4 <= n.bytes.size) b.putInt(off, sh(b.getInt(off))) }
                fix(12) // comment
                when (n.type) {
                    START_NS, END_NS, END_ELEMENT -> { fix(16); fix(20) }
                    CDATA -> { fix(16); if (n.bytes.size >= 28 && (n.bytes[23].toInt() and 0xFF) == TYPE_STRING) fix(24) }
                }
            }
        }
    }

    fun setString(e: Element, resId: Int, value: String, plainName: String? = null) {
        val idx = intern(value)
        val a = attr(e, resId, plainName)
        if (a != null) { a.raw = idx; a.type = TYPE_STRING; a.data = idx; return }
        val nameIdx = if (resId != 0) attrNameIndex(resId) else intern(plainName ?: error("name"))
        insertAttr(e, Attr(if (resId != 0) androidNs else -1, nameIdx, idx, TYPE_STRING, idx))
    }

    fun setInt(e: Element, resId: Int, value: Int) = setTyped(e, resId, TYPE_INT_DEC, value)
    fun setBool(e: Element, resId: Int, value: Boolean) = setTyped(e, resId, TYPE_INT_BOOLEAN, if (value) -1 else 0)

    private fun setTyped(e: Element, resId: Int, type: Int, data: Int) {
        val a = attr(e, resId)
        if (a != null) { a.raw = -1; a.type = type; a.data = data; return }
        insertAttr(e, Attr(androidNs, attrNameIndex(resId), -1, type, data))
    }

    fun removeAttr(e: Element, resId: Int): Boolean {
        val a = attr(e, resId) ?: return false
        val pos = e.attrs.indexOf(a) + 1
        e.attrs.remove(a)
        fun fix(i: Int) = when { i == pos -> 0; i > pos -> i - 1; else -> i }
        e.idIndex = fix(e.idIndex); e.classIndex = fix(e.classIndex); e.styleIndex = fix(e.styleIndex)
        return true
    }

    /** Attributes must stay sorted by resource id (the framework binary-searches them). */
    private fun insertAttr(e: Element, a: Attr) {
        val key = sortKey(a)
        var at = e.attrs.indexOfFirst { sortKey(it) > key }
        if (at < 0) at = e.attrs.size
        e.attrs.add(at, a)
        val pos = at + 1
        fun fix(i: Int) = if (i >= pos && i != 0) i + 1 else i
        e.idIndex = fix(e.idIndex); e.classIndex = fix(e.classIndex); e.styleIndex = fix(e.styleIndex)
    }

    private fun sortKey(a: Attr): Long = resIdOf(a.name).let { if (it != 0) it.toLong() and 0xFFFFFFFFL else Long.MAX_VALUE }

    /**
     * Inserts `<name android:name="value"/>` (or `<name/>` when [nameValue] is null) as a direct
     * child of `<manifest>`, before `<application>`.
     */
    fun addManifestChild(name: String, nameValue: String?): Element {
        val attrs = mutableListOf<Attr>()
        if (nameValue != null) {
            val nameAttr = attrNameIndex(ATTR_NAME)
            val v = intern(nameValue)
            attrs += Attr(androidNs, nameAttr, v, TYPE_STRING, v)
        }
        val el = Element(
            line = 0, comment = -1, ns = -1, name = intern(name), idIndex = 0, classIndex = 0, styleIndex = 0,
            attrs = attrs,
        )
        val app = nodes.indexOfFirst { it is Element && elementName(it) == "application" }
        val at = if (app >= 0) app else nodes.indexOfLast { it is Raw && it.type == END_ELEMENT }
        require(at >= 0) { "Malformed manifest" }
        nodes.add(at, el)
        nodes.add(at + 1, endElement(el))
        return el
    }

    /** Removes [e] and everything up to its matching END_ELEMENT. */
    fun removeElement(e: Element) {
        val start = nodes.indexOf(e)
        require(start >= 0)
        var depth = 0
        var i = start
        while (i < nodes.size) {
            val n = nodes[i]
            if (n is Element) depth++
            if (n is Raw && n.type == END_ELEMENT) { depth--; if (depth == 0) break }
            i++
        }
        require(depth == 0) { "Unbalanced element" }
        repeat(i - start + 1) { nodes.removeAt(start) }
    }

    /** Parent chain helper: the elements enclosing each element, by document order. */
    fun parentOf(e: Element): Element? {
        val stack = ArrayDeque<Element>()
        for (n in nodes) {
            if (n === e) return stack.lastOrNull()
            if (n is Element) stack.addLast(n)
            if (n is Raw && n.type == END_ELEMENT) stack.removeLastOrNull()
        }
        return null
    }

    private fun endElement(e: Element): Raw {
        val b = le(24) {
            putShort(END_ELEMENT.toShort()); putShort(16); putInt(24)
            putInt(e.line); putInt(-1); putInt(e.ns); putInt(e.name)
        }
        return Raw(END_ELEMENT, b)
    }

    fun encode(): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(pool.encode())
        if (resIds.isNotEmpty()) {
            body.write(le(8 + resIds.size * 4) {
                putShort(RES_MAP.toShort()); putShort(8); putInt(8 + resIds.size * 4)
                resIds.forEach { putInt(it) }
            })
        }
        for (n in nodes) when (n) {
            is Raw -> body.write(n.bytes)
            is Element -> {
                val size = 16 + 20 + n.attrs.size * 20
                body.write(le(size) {
                    putShort(START_ELEMENT.toShort()); putShort(16); putInt(size)
                    putInt(n.line); putInt(n.comment)
                    putInt(n.ns); putInt(n.name)
                    putShort(20); putShort(20); putShort(n.attrs.size.toShort())
                    putShort(n.idIndex.toShort()); putShort(n.classIndex.toShort()); putShort(n.styleIndex.toShort())
                    for (a in n.attrs) {
                        putInt(a.ns); putInt(a.name); putInt(a.raw)
                        putShort(8); put(0); put(a.type.toByte()); putInt(a.data)
                    }
                })
            }
        }
        val total = 8 + body.size()
        return le(8) { putShort(RES_XML.toShort()); putShort(8); putInt(total) } + body.toByteArray()
    }

    companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val RES_XML = 0x0003
        const val RES_STRING_POOL = 0x0001
        const val RES_MAP = 0x0180
        const val START_NS = 0x0100
        const val END_NS = 0x0101
        const val START_ELEMENT = 0x0102
        const val END_ELEMENT = 0x0103
        const val CDATA = 0x0104

        const val TYPE_STRING = 0x03
        const val TYPE_INT_DEC = 0x10
        const val TYPE_INT_BOOLEAN = 0x12

        // Framework attribute ids (android.R.attr), stable since their API level.
        const val ATTR_NAME = 0x01010003
        const val ATTR_EXPORTED = 0x01010010
        const val ATTR_DEBUGGABLE = 0x0101000f
        const val ATTR_VERSION_CODE = 0x0101021b
        const val ATTR_VERSION_NAME = 0x0101021c
        const val ATTR_MIN_SDK = 0x0101020c
        const val ATTR_TARGET_SDK = 0x01010270
        const val ATTR_AUTHORITIES = 0x01010018
        const val ATTR_PERMISSION = 0x01010006

        /** Framework attributes this editor may add when a manifest lacks them. */
        val FRAMEWORK_ATTRS: Map<Int, String> = mapOf(
            ATTR_NAME to "name",
            ATTR_PERMISSION to "permission",
            ATTR_DEBUGGABLE to "debuggable",
            ATTR_EXPORTED to "exported",
            ATTR_AUTHORITIES to "authorities",
            ATTR_MIN_SDK to "minSdkVersion",
            ATTR_VERSION_CODE to "versionCode",
            ATTR_VERSION_NAME to "versionName",
            ATTR_TARGET_SDK to "targetSdkVersion",
        )

        private fun le(n: Int, f: ByteBuffer.() -> Unit) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN).apply(f).array()

        fun parse(bytes: ByteArray): AxmlEditor {
            require(AxmlDecoder.isBinaryXml(bytes)) { "Not a binary XML file" }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var pool: StringPool? = null
            var resIds = IntArray(0)
            val nodes = mutableListOf<Node>()
            val end = minOf(bytes.size, buf.getInt(4).takeIf { it in 8..bytes.size } ?: bytes.size)
            var pos = buf.getShort(2).toInt() and 0xFFFF
            while (pos + 8 <= end) {
                val type = buf.getShort(pos).toInt() and 0xFFFF
                val headerSize = buf.getShort(pos + 2).toInt() and 0xFFFF
                val size = buf.getInt(pos + 4)
                require(size >= 8 && pos + size <= end) { "Corrupt AXML chunk at $pos" }
                when (type) {
                    RES_STRING_POOL -> pool = StringPool.decode(bytes, pos)
                    RES_MAP -> resIds = IntArray((size - headerSize) / 4) { buf.getInt(pos + headerSize + it * 4) }
                    START_ELEMENT -> {
                        val ext = pos + headerSize
                        val attrStart = buf.getShort(ext + 8).toInt() and 0xFFFF
                        val attrSize = buf.getShort(ext + 10).toInt() and 0xFFFF
                        val count = buf.getShort(ext + 12).toInt() and 0xFFFF
                        val attrs = MutableList(count) { i ->
                            val a = ext + attrStart + i * attrSize
                            Attr(buf.getInt(a), buf.getInt(a + 4), buf.getInt(a + 8), bytes[a + 15].toInt() and 0xFF, buf.getInt(a + 16))
                        }
                        nodes += Element(
                            buf.getInt(pos + 8), buf.getInt(pos + 12), buf.getInt(ext), buf.getInt(ext + 4),
                            buf.getShort(ext + 14).toInt() and 0xFFFF, buf.getShort(ext + 16).toInt() and 0xFFFF,
                            buf.getShort(ext + 18).toInt() and 0xFFFF, attrs,
                        )
                    }
                    else -> nodes += Raw(type, bytes.copyOfRange(pos, pos + size))
                }
                pos += size
            }
            return AxmlEditor(pool ?: error("AXML has no string pool"), resIds, nodes)
        }
    }
}
