package ai.victorious.filemanager.core.tags

import ai.victorious.filemanager.core.fs.FileIo
import ai.victorious.filemanager.core.fs.JsonStateFile
import ai.victorious.filemanager.core.fs.NioFileIo
import ai.victorious.filemanager.core.path.PathHelpers
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer

/** Seven colour labels (Finder-style). Stored per path; a file can carry several. */
enum class ColorTag(val label: String, val argb: Long) {
    RED("Red", 0xFFE53935), ORANGE("Orange", 0xFFFB8C00), YELLOW("Yellow", 0xFFFDD835),
    GREEN("Green", 0xFF43A047), BLUE("Blue", 0xFF1E88E5), PURPLE("Purple", 0xFF8E24AA), GREY("Grey", 0xFF757575),
}

class ColorTagStore(storeFile: String, io: FileIo = NioFileIo()) {
    private val state = JsonStateFile(io, storeFile, MapSerializer(String.serializer(), SetSerializer(String.serializer())), emptyMap())

    @Synchronized fun tagsOf(path: String): Set<ColorTag> =
        state.load()[PathHelpers.normalize(path)].orEmpty().mapNotNull { n -> ColorTag.entries.firstOrNull { it.name == n } }.toSet()

    @Synchronized fun set(path: String, tags: Set<ColorTag>) {
        val p = PathHelpers.normalize(path)
        val m = state.load().toMutableMap()
        if (tags.isEmpty()) m.remove(p) else m[p] = tags.map { it.name }.toSortedSet()
        state.save(m)
    }

    @Synchronized fun toggle(path: String, tag: ColorTag): Set<ColorTag> {
        val now = tagsOf(path).let { if (tag in it) it - tag else it + tag }
        set(path, now); return now
    }

    @Synchronized fun pathsWith(tag: ColorTag): List<String> =
        state.load().filterValues { tag.name in it }.keys.sorted()

    @Synchronized fun all(): Map<String, Set<ColorTag>> = state.load().keys.associateWith { tagsOf(it) }

    /** Keep tags attached when a file is renamed/moved inside the app (also children of a moved folder). */
    @Synchronized fun onMoved(from: String, to: String) {
        val f = PathHelpers.normalize(from); val t = PathHelpers.normalize(to)
        val m = state.load()
        val moved = m.mapKeys { (k, _) ->
            when {
                k == f -> t
                k.startsWith("$f/") -> t + k.removePrefix(f)
                else -> k
            }
        }
        if (moved != m) state.save(moved)
    }

    /** Drop entries whose file no longer exists. Returns how many were removed. */
    @Synchronized fun prune(exists: (String) -> Boolean): Int {
        val m = state.load()
        val kept = m.filterKeys(exists)
        if (kept.size != m.size) state.save(kept)
        return m.size - kept.size
    }
}
