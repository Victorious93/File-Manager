package ai.victorious.filemanager.core.apk

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/** Metadata pulled from a binary AndroidManifest.xml. */
data class ApkManifestInfo(
    val packageName: String?,
    val versionCode: Long?,
    val versionName: String?,
    val minSdk: Int?,
    val targetSdk: Int?,
    val permissions: List<String>,
)

/**
 * Minimal, dependency-free reader for Android binary XML (AXML) as found in
 * AndroidManifest.xml inside an APK. Only what is needed for APK info:
 * string pool, resource-id map and start-element attributes.
 */
object AxmlParser {
    private const val RES_STRING_POOL = 0x0001
    private const val RES_XML = 0x0003
    private const val RES_XML_RESOURCE_MAP = 0x0180
    private const val RES_XML_START_ELEMENT = 0x0102
    private const val UTF8_FLAG = 0x100

    const val ATTR_NAME = 0x01010003
    const val ATTR_VERSION_CODE = 0x0101021b
    const val ATTR_VERSION_NAME = 0x0101021c
    const val ATTR_MIN_SDK = 0x0101020c
    const val ATTR_TARGET_SDK = 0x01010270

    data class Attr(val name: String, val resId: Int?, val value: String?)
    data class Element(val name: String, val attrs: List<Attr>) {
        fun attr(name: String, resId: Int): String? =
            attrs.firstOrNull { (it.resId != null && it.resId == resId) || it.name == name }?.value
    }

    fun fromApk(apk: File): ApkManifestInfo = ZipFile(apk).use { zip ->
        val entry = zip.getEntry("AndroidManifest.xml") ?: throw IllegalArgumentException("No AndroidManifest.xml in ${apk.name}")
        parseManifest(zip.getInputStream(entry).use { it.readBytes() })
    }

    fun parseManifest(bytes: ByteArray): ApkManifestInfo {
        val elements = parseElements(bytes)
        val manifest = elements.firstOrNull { it.name == "manifest" }
        val usesSdk = elements.firstOrNull { it.name == "uses-sdk" }
        val perms = elements.filter { it.name == "uses-permission" || it.name == "uses-permission-sdk-23" }
            .mapNotNull { it.attr("name", ATTR_NAME) }
            .distinct()
        return ApkManifestInfo(
            packageName = manifest?.attrs?.firstOrNull { it.name == "package" }?.value,
            versionCode = manifest?.attr("versionCode", ATTR_VERSION_CODE)?.toLongOrNull(),
            versionName = manifest?.attr("versionName", ATTR_VERSION_NAME),
            minSdk = usesSdk?.attr("minSdkVersion", ATTR_MIN_SDK)?.toIntOrNull(),
            targetSdk = usesSdk?.attr("targetSdkVersion", ATTR_TARGET_SDK)?.toIntOrNull(),
            permissions = perms,
        )
    }

    fun parseElements(bytes: ByteArray): List<Element> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= 8 && (buf.getShort(0).toInt() and 0xFFFF) == RES_XML) { "Not a binary XML file" }
        var strings: List<String> = emptyList()
        var resIds = IntArray(0)
        val out = mutableListOf<Element>()
        var pos = buf.getShort(2).toInt() and 0xFFFF
        while (pos + 8 <= bytes.size) {
            val type = buf.getShort(pos).toInt() and 0xFFFF
            val headerSize = buf.getShort(pos + 2).toInt() and 0xFFFF
            val size = buf.getInt(pos + 4)
            if (size < 8 || pos + size > bytes.size) break
            when (type) {
                RES_STRING_POOL -> strings = readStringPool(buf, pos)
                RES_XML_RESOURCE_MAP -> resIds = IntArray((size - headerSize) / 4) { buf.getInt(pos + headerSize + it * 4) }
                RES_XML_START_ELEMENT -> {
                    val ext = pos + headerSize
                    val name = strings.getOrElse(buf.getInt(ext + 4)) { "" }
                    val attrStart = buf.getShort(ext + 8).toInt() and 0xFFFF
                    val attrSize = buf.getShort(ext + 10).toInt() and 0xFFFF
                    val attrCount = buf.getShort(ext + 12).toInt() and 0xFFFF
                    val attrs = (0 until attrCount).map { i ->
                        val a = ext + attrStart + i * attrSize
                        val nameIdx = buf.getInt(a + 4)
                        val raw = buf.getInt(a + 8)
                        val dataType = bytes[a + 15].toInt() and 0xFF
                        val data = buf.getInt(a + 16)
                        val value = when {
                            raw >= 0 -> strings.getOrNull(raw)
                            dataType == 0x03 -> strings.getOrNull(data)
                            dataType == 0x10 -> data.toString()
                            dataType == 0x11 -> "0x" + Integer.toHexString(data)
                            dataType == 0x12 -> (data != 0).toString()
                            dataType == 0x01 -> "@0x" + Integer.toHexString(data)
                            else -> data.toString()
                        }
                        Attr(strings.getOrElse(nameIdx) { "" }, resIds.getOrNull(nameIdx), value)
                    }
                    out += Element(name, attrs)
                }
            }
            pos += size
        }
        return out
    }

    internal fun readStringPool(buf: ByteBuffer, start: Int): List<String> {
        val headerSize = buf.getShort(start + 2).toInt() and 0xFFFF
        val count = buf.getInt(start + 8)
        val flags = buf.getInt(start + 16)
        val stringsStart = start + buf.getInt(start + 20)
        val utf8 = flags and UTF8_FLAG != 0
        return (0 until count).map { i ->
            var p = stringsStart + buf.getInt(start + headerSize + i * 4)
            if (utf8) {
                // char length (skip), then byte length; each 1 or 2 bytes
                p += if (buf.get(p).toInt() and 0x80 != 0) 2 else 1
                var len = buf.get(p).toInt() and 0xFF
                if (len and 0x80 != 0) { len = ((len and 0x7F) shl 8) or (buf.get(p + 1).toInt() and 0xFF); p += 2 } else p += 1
                String(buf.array(), p, len, Charsets.UTF_8)
            } else {
                var len = buf.getShort(p).toInt() and 0xFFFF
                if (len and 0x8000 != 0) { len = ((len and 0x7FFF) shl 16) or (buf.getShort(p + 2).toInt() and 0xFFFF); p += 4 } else p += 2
                String(buf.array(), p, len * 2, Charsets.UTF_16LE)
            }
        }
    }
}
