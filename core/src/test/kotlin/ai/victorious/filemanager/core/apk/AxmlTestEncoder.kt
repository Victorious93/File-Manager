package ai.victorious.filemanager.core.apk

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Test-only encoder producing a minimal but valid binary AndroidManifest.xml. */
object AxmlTestEncoder {
    private const val NS = "http://schemas.android.com/apk/res/android"

    fun manifest(pkg: String, versionCode: Int, versionName: String, minSdk: Int, targetSdk: Int, perms: List<String>, utf8: Boolean = false): ByteArray {
        val resAttrs = listOf("name" to AxmlParser.ATTR_NAME, "versionCode" to AxmlParser.ATTR_VERSION_CODE,
            "versionName" to AxmlParser.ATTR_VERSION_NAME, "minSdkVersion" to AxmlParser.ATTR_MIN_SDK,
            "targetSdkVersion" to AxmlParser.ATTR_TARGET_SDK)
        val strings = resAttrs.map { it.first }.toMutableList()
        fun idx(s: String): Int { val i = strings.indexOf(s); if (i >= 0) return i; strings += s; return strings.lastIndex }
        val ns = idx(NS)
        idx("android")
        // (nsIdx, nameIdx, raw, type, data)
        class A(val ns: Int, val name: Int, val raw: Int, val type: Int, val data: Int)
        fun str(nsI: Int, n: String, v: String) = idx(v).let { A(nsI, idx(n), it, 0x03, it) }
        fun int(n: String, v: Int) = A(ns, idx(n), -1, 0x10, v)
        val elements = mutableListOf<Pair<Int, List<A>>>()
        elements += idx("manifest") to listOf(int("versionCode", versionCode), str(ns, "versionName", versionName), str(-1, "package", pkg))
        elements += idx("uses-sdk") to listOf(int("minSdkVersion", minSdk), int("targetSdkVersion", targetSdk))
        perms.forEach { elements += idx("uses-permission") to listOf(str(ns, "name", it)) }

        val body = ByteArrayOutputStream()
        body.write(stringPool(strings, utf8))
        body.write(chunk(0x0180, 8, ByteBuffer.allocate(resAttrs.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { resAttrs.forEach { putInt(it.second) } }.array()))
        body.write(chunk(0x0100, 16, le(16) { putInt(1); putInt(-1); putInt(idx("android")); putInt(ns) }))
        for ((name, attrs) in elements) {
            val b = ByteBuffer.allocate(8 + 20 + attrs.size * 20).order(ByteOrder.LITTLE_ENDIAN)
            b.putInt(1); b.putInt(-1)
            b.putInt(-1); b.putInt(name); b.putShort(20); b.putShort(20); b.putShort(attrs.size.toShort())
            b.putShort(0); b.putShort(0); b.putShort(0)
            attrs.forEach { a -> b.putInt(a.ns); b.putInt(a.name); b.putInt(a.raw); b.putShort(8); b.put(0); b.put(a.type.toByte()); b.putInt(a.data) }
            body.write(chunk(0x0102, 16, b.array().copyOfRange(8, b.capacity()), b.array().copyOfRange(0, 8)))
            if (name != elements.first().first) body.write(chunk(0x0103, 16, le(8) { putInt(-1); putInt(name) }.copyOfRange(0, 8), le(8) { putInt(1); putInt(-1) }))
        }
        body.write(chunk(0x0103, 16, le(8) { putInt(-1); putInt(elements.first().first) }, le(8) { putInt(1); putInt(-1) }))
        body.write(chunk(0x0101, 16, le(16) { putInt(1); putInt(-1); putInt(idx("android")); putInt(ns) }))
        return chunk(0x0003, 8, body.toByteArray())
    }

    private fun le(n: Int, f: ByteBuffer.() -> Unit) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN).apply(f).array()

    /** chunk with header [type, headerSize, size] + extra header bytes [headerExtra] + body. */
    private fun chunk(type: Int, headerSize: Int, body: ByteArray, headerExtra: ByteArray? = null): ByteArray {
        val extra = headerExtra ?: if (headerSize > 8) body.copyOfRange(0, headerSize - 8) else ByteArray(0)
        val rest = if (headerExtra == null && headerSize > 8) body.copyOfRange(headerSize - 8, body.size) else body
        val total = 8 + extra.size + rest.size
        return le(8) { putShort(type.toShort()); putShort(headerSize.toShort()); putInt(total) } + extra + rest
    }

    private fun stringPool(strings: List<String>, utf8: Boolean): ByteArray {
        val data = ByteArrayOutputStream()
        val offsets = strings.map { s ->
            val off = data.size()
            if (utf8) {
                val b = s.toByteArray(Charsets.UTF_8)
                data.write(s.length); data.write(b.size); data.write(b); data.write(0)
            } else {
                data.write(s.length and 0xFF); data.write(s.length shr 8)
                data.write(s.toByteArray(Charsets.UTF_16LE)); data.write(0); data.write(0)
            }
            off
        }
        while (data.size() % 4 != 0) data.write(0)
        val headerSize = 28
        val stringsStart = headerSize + strings.size * 4
        val h = le(20 + strings.size * 4) {
            putInt(strings.size); putInt(0); putInt(if (utf8) 0x100 else 0); putInt(stringsStart); putInt(0)
            offsets.forEach { putInt(it) }
        }
        val total = 8 + h.size + data.size()
        return le(8) { putShort(1); putShort(headerSize.toShort()); putInt(total) } + h + data.toByteArray()
    }
}
