package ai.victorious.filemanager.core.apk

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Adds brand-new string resources (`string/<name>`) to a resources.arsc: new keys in the
 * package key pool, new values in the global string pool, and new entries in **every**
 * existing string-type configuration (all locales). Existing resource IDs stay stable —
 * new ones are appended at the end of the string type.
 *
 * Compact (sparse / offset16) type tables are rejected, same as [ArscLocaleAdder].
 * Written against the public ResTable format; no MT/NP Manager source.
 */
object ArscStringAdder {
    private const val FLAG_SPARSE = 0x01
    private const val FLAG_OFFSET16 = 0x02

    data class Added(val name: String, val resId: Int, val value: String, val poolIndex: Int)

    data class Result(val bytes: ByteArray, val added: List<Added>)

    /**
     * [newStrings] maps resource name → text. Names must be unique among existing
     * default-locale string resources. Returns new arsc bytes plus the assigned resIds.
     */
    fun addStrings(arscBytes: ByteArray, newStrings: Map<String, String>): Result {
        require(newStrings.isNotEmpty()) { "Nothing to add" }
        val bad = newStrings.keys.filter { !it.matches(Regex("^[A-Za-z_][A-Za-z0-9_]*$")) }
        require(bad.isEmpty()) { "Invalid string resource name(s): ${bad.joinToString()}" }
        val arsc = Arsc.open(arscBytes)
        val existing = arsc.strings.filter { it.locale == "default" }.map { it.name }.toSet()
        val clash = newStrings.keys.filter { it in existing }
        require(clash.isEmpty()) { "Already exists: ${clash.joinToString()}" }

        val ordered = newStrings.entries.toList()
        val buf = ByteBuffer.wrap(arscBytes).order(ByteOrder.LITTLE_ENDIAN)
        val tableHeader = buf.getShort(2).toInt() and 0xFFFF
        var poolStart = -1
        var pos = tableHeader
        while (pos + 8 <= arscBytes.size) {
            if ((buf.getShort(pos).toInt() and 0xFFFF) == Arsc.RES_STRING_POOL) {
                poolStart = pos; break
            }
            pos += buf.getInt(pos + 4).also { require(it >= 8) { "Corrupt chunk" } }
        }
        require(poolStart >= 0) { "No global string pool" }
        val poolSize = buf.getInt(poolStart + 4)
        val pool = StringPool.decode(arscBytes, poolStart)
        val poolIndexOf = IntArray(ordered.size)
        for ((i, e) in ordered.withIndex()) {
            poolIndexOf[i] = pool.strings.size
            pool.strings += e.value
        }
        val newPool = pool.encode()

        val out = ByteArrayOutputStream(arscBytes.size + newPool.size + ordered.size * 64)
        out.write(arscBytes, 0, poolStart)
        out.write(newPool)
        pos = poolStart + poolSize
        var totalAdded = 0
        val added = mutableListOf<Added>()
        while (pos + 8 <= arscBytes.size) {
            val type = buf.getShort(pos).toInt() and 0xFFFF
            val size = buf.getInt(pos + 4)
            require(size >= 8 && pos + size <= arscBytes.size) { "Corrupt chunk at $pos" }
            if (type == Arsc.RES_PACKAGE) {
                val (pkg, n, pkgAdded) = rewritePackage(
                    arscBytes, pos, size, ordered.map { it.key }, ordered.map { it.value }, poolIndexOf,
                )
                out.write(pkg)
                totalAdded += n
                added += pkgAdded
            } else out.write(arscBytes, pos, size)
            pos += size
        }
        check(totalAdded == ordered.size) { "Only $totalAdded of ${ordered.size} strings were placed" }
        val bytes = out.toByteArray()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(4, bytes.size)
        return Result(bytes, added)
    }

    /** Parse `name<TAB>text` / `name=text` lines into a name→text map. */
    fun fromLines(lines: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (raw in lines.lineSequence()) {
            val line = raw.trimEnd()
            if (line.isBlank() || line.trimStart().startsWith("#")) continue
            val cut = line.indexOf('\t').takeIf { it > 0 } ?: line.indexOf('=').takeIf { it > 0 } ?: continue
            val name = line.substring(0, cut).trim().removePrefix("string/").removePrefix("@string/")
            val text = line.substring(cut + 1).trim().replace("\\n", "\n")
            if (name.isNotEmpty()) out[name] = text
        }
        return out
    }

    private fun rewritePackage(
        src: ByteArray,
        start: Int,
        size: Int,
        names: List<String>,
        values: List<String>,
        poolIndexOf: IntArray,
    ): Triple<ByteArray, Int, List<Added>> {
        val buf = ByteBuffer.wrap(src).order(ByteOrder.LITTLE_ENDIAN)
        val headerSize = buf.getShort(start + 2).toInt() and 0xFFFF
        val pkgId = buf.getInt(start + 8)
        val typePoolOff = buf.getInt(start + 12 + 256)
        val keyPoolOff = buf.getInt(start + 12 + 256 + 8)
        val typeStrings = StringPool.decode(src, start + typePoolOff)
        val keyStrings = StringPool.decode(src, start + keyPoolOff)
        val stringTypeId = typeStrings.strings.indexOf("string") + 1
        if (stringTypeId == 0) return Triple(src.copyOfRange(start, start + size), 0, emptyList())

        val keyBase = keyStrings.strings.size
        for (n in names) keyStrings.strings += n
        val newKeyPool = keyStrings.encode()
        val typePoolBytes = src.copyOfRange(start + typePoolOff, start + keyPoolOff)
        // Keep type pool size; key pool may grow. Chunks after pools start at old keyPoolOff + oldKeySize.
        val oldKeySize = buf.getInt(start + keyPoolOff + 4)
        val chunksStart = start + keyPoolOff + oldKeySize

        data class Chunk(val pos: Int, val size: Int, val type: Int, val typeId: Int)
        val chunks = ArrayList<Chunk>()
        var pos = start + headerSize
        // Skip past the two pools inside the package (they sit right after the header).
        // Prefer walking from header: type pool then key pool then specs/types.
        pos = start + typePoolOff
        val typePoolSize = buf.getInt(pos + 4)
        pos += typePoolSize
        val keyPoolSize = buf.getInt(pos + 4)
        pos += keyPoolSize
        while (pos + 8 <= start + size) {
            val t = buf.getShort(pos).toInt() and 0xFFFF
            val cs = buf.getInt(pos + 4)
            require(cs >= 8 && pos + cs <= start + size) { "Corrupt package chunk" }
            val tid = if (t == Arsc.RES_TABLE_TYPE || t == Arsc.RES_TABLE_TYPE_SPEC) {
                buf.get(pos + 8).toInt() and 0xFF
            } else 0
            chunks += Chunk(pos, cs, t, tid)
            pos += cs
        }

        val oldEntryCount = chunks
            .firstOrNull { it.type == Arsc.RES_TABLE_TYPE_SPEC && it.typeId == stringTypeId }
            ?.let { buf.getInt(it.pos + 12) }
            ?: return Triple(src.copyOfRange(start, start + size), 0, emptyList())

        val added = names.mapIndexed { i, name ->
            Added(
                name,
                (pkgId shl 24) or (stringTypeId shl 16) or (oldEntryCount + i),
                values[i],
                poolIndexOf[i],
            )
        }

        val body = ByteArrayOutputStream()
        body.write(typePoolBytes)
        body.write(newKeyPool)
        for (c in chunks) {
            if (c.type == Arsc.RES_TABLE_TYPE_SPEC && c.typeId == stringTypeId) {
                body.write(expandTypeSpec(src, c.pos, names.size))
            } else if (c.type == Arsc.RES_TABLE_TYPE && c.typeId == stringTypeId) {
                body.write(expandType(src, c.pos, names.size, keyBase, poolIndexOf))
            } else {
                body.write(src, c.pos, c.size)
            }
        }
        val tail = chunks.lastOrNull()?.let { it.pos + it.size } ?: chunksStart
        if (tail < start + size) body.write(src, tail, start + size - tail)

        val bodyBytes = body.toByteArray()
        val newPkgSize = headerSize + bodyBytes.size
        val out = ByteBuffer.allocate(newPkgSize).order(ByteOrder.LITTLE_ENDIAN)
        out.put(src, start, headerSize)
        out.putInt(4, newPkgSize)
        // typeStrings offset unchanged (still headerSize / typePoolOff)
        out.putInt(12 + 256, typePoolOff)
        out.putInt(12 + 256 + 4, typeStrings.strings.size) // lastPublicType
        out.putInt(12 + 256 + 8, typePoolOff + typePoolBytes.size) // keyStrings
        out.putInt(12 + 256 + 12, keyBase + names.size) // lastPublicKey
        out.position(headerSize)
        out.put(bodyBytes)
        return Triple(out.array(), names.size, added)
    }

    private fun expandTypeSpec(src: ByteArray, start: Int, add: Int): ByteArray {
        val buf = ByteBuffer.wrap(src).order(ByteOrder.LITTLE_ENDIAN)
        val headerSize = buf.getShort(start + 2).toInt() and 0xFFFF
        val oldCount = buf.getInt(start + 12)
        val newCount = oldCount + add
        val total = headerSize + newCount * 4
        val o = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        o.put(src, start, headerSize + oldCount * 4)
        o.putInt(4, total)
        o.putInt(12, newCount)
        o.position(headerSize + oldCount * 4)
        repeat(add) { o.putInt(0) }
        return o.array()
    }

    private fun expandType(
        src: ByteArray,
        start: Int,
        add: Int,
        keyBase: Int,
        poolIndexOf: IntArray,
    ): ByteArray {
        val buf = ByteBuffer.wrap(src).order(ByteOrder.LITTLE_ENDIAN)
        val headerSize = buf.getShort(start + 2).toInt() and 0xFFFF
        val flags = buf.get(start + 9).toInt() and 0xFF
        require(flags and (FLAG_SPARSE or FLAG_OFFSET16) == 0) {
            "This APK uses a compact resource table format that is not supported yet"
        }
        val oldCount = buf.getInt(start + 12)
        val oldEntriesStart = buf.getInt(start + 16)
        val oldSize = buf.getInt(start + 4)
        val newCount = oldCount + add
        val newEntriesStart = headerSize + newCount * 4
        // Collect old entry payloads in order of their offsets.
        val oldPayload = src.copyOfRange(start + oldEntriesStart, start + oldSize)
        val entrySize = 16
        val total = newEntriesStart + oldPayload.size + add * entrySize
        val o = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        o.put(src, start, headerSize)
        o.putInt(4, total)
        o.putInt(12, newCount)
        o.putInt(16, newEntriesStart)
        o.position(headerSize)
        // Remap old offsets: they were relative to oldEntriesStart; new payload starts at newEntriesStart.
        for (i in 0 until oldCount) {
            val off = buf.getInt(start + headerSize + i * 4)
            if (off == -1) o.putInt(-1)
            else o.putInt(off) // still relative to entriesStart; payload copied as-is
        }
        var next = oldPayload.size
        for (i in 0 until add) {
            o.putInt(next)
            next += entrySize
        }
        o.put(oldPayload)
        for (i in 0 until add) {
            o.putShort(8) // entry size
            o.putShort(0) // flags
            o.putInt(keyBase + i) // key index
            o.putShort(8).put(0).put(0x03.toByte()).putInt(poolIndexOf[i])
        }
        check(o.position() == total) { "type size mismatch ${o.position()} vs $total" }
        return o.array()
    }
}
