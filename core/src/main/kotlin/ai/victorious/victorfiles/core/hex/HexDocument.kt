package ai.victorious.victorfiles.core.hex

import java.io.File
import java.io.RandomAccessFile

/**
 * In-place byte editor (overwrite mode, length is fixed). Edits are tracked as a patch map so
 * saving only rewrites changed bytes; undo pops the last edit.
 */
class HexDocument(private val original: ByteArray) {
    private val patches = LinkedHashMap<Int, Byte>()
    private val history = ArrayDeque<Pair<Int, Byte?>>()

    val size: Int get() = original.size
    val isDirty: Boolean get() = patches.isNotEmpty()
    val canUndo: Boolean get() = history.isNotEmpty()
    val changedOffsets: Set<Int> get() = patches.keys

    operator fun get(offset: Int): Byte = patches[offset] ?: original[offset]

    fun set(offset: Int, value: Byte) {
        require(offset in 0 until size) { "Offset $offset out of range" }
        history.addLast(offset to patches[offset])
        if (original[offset] == value) patches.remove(offset) else patches[offset] = value
    }

    fun undo() {
        val (offset, previous) = history.removeLastOrNull() ?: return
        if (previous == null) patches.remove(offset) else patches[offset] = previous
    }

    fun bytes(): ByteArray = original.copyOf().also { out -> patches.forEach { (o, v) -> out[o] = v } }

    fun row(rowIndex: Int, width: Int = BYTES_PER_ROW): HexRow {
        val start = rowIndex * width
        val end = minOf(start + width, size)
        val values = (start until end).map { get(it) }
        return HexRow(start, values)
    }

    val rowCount: Int get() = (size + BYTES_PER_ROW - 1) / BYTES_PER_ROW

    fun saveTo(file: File) {
        RandomAccessFile(file, "rw").use { raf ->
            for ((offset, value) in patches.toSortedMap()) {
                raf.seek(offset.toLong()); raf.write(value.toInt())
            }
        }
        val committed = bytes()
        committed.copyInto(original)
        patches.clear(); history.clear()
    }

    companion object {
        const val BYTES_PER_ROW = 16
        const val MAX_BYTES = 16 * 1024 * 1024

        fun load(file: File): HexDocument {
            require(file.length() <= MAX_BYTES) { "File too large for hex editing (max 16 MB)" }
            return HexDocument(file.readBytes())
        }
    }
}

data class HexRow(val offset: Int, val values: List<Byte>) {
    val offsetLabel: String get() = "%08X".format(offset)
    val hex: String get() = values.joinToString(" ") { Hex.byte(it) }
    val ascii: String get() = values.joinToString("") { b ->
        val v = b.toInt() and 0xFF
        if (v in 0x20..0x7E) v.toChar().toString() else "."
    }
}

object Hex {
    private const val DIGITS = "0123456789ABCDEF"
    fun byte(b: Byte): String {
        val v = b.toInt() and 0xFF
        return "${DIGITS[v shr 4]}${DIGITS[v and 0xF]}"
    }

    /** Parses one or two hex digits ("a", "0F", "ff") into a byte; null if invalid. */
    fun parseByte(text: String): Byte? {
        val t = text.trim()
        if (t.isEmpty() || t.length > 2) return null
        return t.toIntOrNull(16)?.toByte()
    }
}
