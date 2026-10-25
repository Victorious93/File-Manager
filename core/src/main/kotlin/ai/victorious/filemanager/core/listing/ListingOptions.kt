package ai.victorious.filemanager.core.listing

import ai.victorious.filemanager.core.fs.FileEntry
import ai.victorious.filemanager.core.path.PathHelpers
import java.util.Locale

enum class SortKey { NAME, SIZE, MODIFIED, TYPE }

/** How a folder listing is sorted and filtered for display. Does not touch the files. */
data class ListingOptions(
    val sortKey: SortKey = SortKey.NAME,
    val ascending: Boolean = true,
    val foldersFirst: Boolean = true,
    val showHidden: Boolean = true,
    val filter: String = "",
) {
    fun apply(entries: List<FileEntry>): List<FileEntry> {
        val needle = filter.trim()
        val visible = entries.filter { e ->
            (showHidden || !e.isHidden) && (needle.isEmpty() || e.name.contains(needle, ignoreCase = true))
        }
        val base: Comparator<FileEntry> = when (sortKey) {
            SortKey.NAME -> Comparator { a, b -> naturalCompare(a.name, b.name) }
            SortKey.SIZE -> compareBy<FileEntry> { if (it.isDirectory) 0L else it.sizeBytes }.thenComparing { a, b -> naturalCompare(a.name, b.name) }
            SortKey.MODIFIED -> compareBy<FileEntry> { it.modifiedEpochMs }.thenComparing { a, b -> naturalCompare(a.name, b.name) }
            SortKey.TYPE -> compareBy<FileEntry> { extension(it) }.thenComparing { a, b -> naturalCompare(a.name, b.name) }
        }
        val directed = if (ascending) base else base.reversed()
        val cmp = if (foldersFirst) Comparator<FileEntry> { a, b ->
            if (a.isDirectory != b.isDirectory) (if (a.isDirectory) -1 else 1) else directed.compare(a, b)
        } else directed
        return visible.sortedWith(cmp)
    }

    private fun extension(e: FileEntry): String =
        if (e.isDirectory) "" else PathHelpers.splitBaseExt(e.name).second.lowercase(Locale.ROOT)

    companion object {
        /** Case-insensitive compare where digit runs compare numerically: file2 < file10. */
        fun naturalCompare(a: String, b: String): Int {
            var i = 0
            var j = 0
            while (i < a.length && j < b.length) {
                val ca = a[i]
                val cb = b[j]
                if (ca.isDigit() && cb.isDigit()) {
                    var ie = i
                    while (ie < a.length && a[ie].isDigit()) ie++
                    var je = j
                    while (je < b.length && b[je].isDigit()) je++
                    val na = a.substring(i, ie).trimStart('0')
                    val nb = b.substring(j, je).trimStart('0')
                    if (na.length != nb.length) return na.length - nb.length
                    val c = na.compareTo(nb)
                    if (c != 0) return c
                    i = ie
                    j = je
                } else {
                    val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                    if (c != 0) return c
                    i++
                    j++
                }
            }
            val rest = (a.length - i) - (b.length - j)
            return if (rest != 0) rest else a.compareTo(b)
        }
    }
}
