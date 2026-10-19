package ai.victorious.victorfiles.core.props

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/** Unix permission bits (the low 9: rwxrwxrwx) with octal and symbolic forms. */
@JvmInline
value class FileMode(val bits: Int) {
    init { require(bits in 0..0b111_111_111) { "Mode out of range: ${bits.toString(8)}" } }

    val octal: String get() = "%03o".format(bits)

    /** e.g. `rwxr-xr--`. */
    val symbolic: String
        get() = buildString {
            for (shift in intArrayOf(6, 3, 0)) {
                val t = (bits shr shift) and 7
                append(if (t and 4 != 0) 'r' else '-')
                append(if (t and 2 != 0) 'w' else '-')
                append(if (t and 1 != 0) 'x' else '-')
            }
        }

    fun toPosix(): Set<PosixFilePermission> {
        val all = PosixFilePermission.entries // OWNER_READ, OWNER_WRITE, OWNER_EXECUTE, GROUP_*, OTHERS_*
        return all.filterIndexed { i, _ -> bits and (1 shl (8 - i)) != 0 }.toSet()
    }

    companion object {
        /** Accepts `755`, `0755`, or `rwxr-xr-x`. */
        fun parse(text: String): FileMode {
            val t = text.trim()
            if (t.matches(Regex("[0-7]{3,4}"))) return FileMode(t.toInt(8) and 0b111_111_111)
            if (t.matches(Regex("[r-][w-][x-][r-][w-][x-][r-][w-][x-]"))) {
                var bits = 0
                t.forEachIndexed { i, c -> if (c != '-') bits = bits or (1 shl (8 - i)) }
                return FileMode(bits)
            }
            throw IllegalArgumentException("Not a mode: $text")
        }

        fun fromPosix(perms: Set<PosixFilePermission>): FileMode {
            var bits = 0
            PosixFilePermission.entries.forEachIndexed { i, p -> if (p in perms) bits = bits or (1 shl (8 - i)) }
            return FileMode(bits)
        }

        /** Reads the mode of a local file, or null when the filesystem has no POSIX permissions. */
        fun read(file: File): FileMode? =
            runCatching { fromPosix(Files.getPosixFilePermissions(file.toPath())) }.getOrNull()

        /** Applies [mode] to a local file, recursing into folders when [recursive] (symlinks are skipped). */
        fun apply(file: File, mode: FileMode, recursive: Boolean = false): Int {
            var n = 0
            fun one(f: File) {
                if (Files.isSymbolicLink(f.toPath())) return
                Files.setPosixFilePermissions(f.toPath(), mode.toPosix())
                n++
                if (recursive && f.isDirectory) f.listFiles()?.forEach(::one)
            }
            one(file)
            return n
        }
    }
}
