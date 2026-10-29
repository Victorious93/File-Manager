package ai.victorious.filemanager.core.notes

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Vault = any folder of `.md` notes. Templates, daily notes and frontmatter helpers.
 * File IO is injected so unit tests stay pure.
 */
interface NoteIo {
    fun listMarkdown(root: String): List<String>
    fun read(path: String): String
    fun write(path: String, text: String)
    fun exists(path: String): Boolean
    fun mkdir(path: String)
    /** Rename a note in place (same folder). Return false when unsupported. */
    fun rename(from: String, to: String): Boolean = false
}

data class VaultConfig(
    val root: String,
    val dailyFormat: String = "yyyy-MM-dd",
    val dailyFolder: String = "Daily",
    val templatesFolder: String = "Templates",
)

class Vault(private val io: NoteIo, var config: VaultConfig) {
    val index = BacklinkIndex()

    fun rebuildIndex() {
        index.clear()
        for (p in io.listMarkdown(config.root)) {
            runCatching { index.setNote(p, io.read(p)) }
        }
    }

    fun open(path: String): String = io.read(path)
    fun save(path: String, body: String) {
        io.write(path, body)
        index.setNote(path, body)
    }

    fun dailyPath(date: LocalDate = LocalDate.now()): String {
        val name = date.format(DateTimeFormatter.ofPattern(config.dailyFormat)) + ".md"
        val folder = config.root.trimEnd('/') + "/" + config.dailyFolder.trim('/')
        return "$folder/$name"
    }

    fun ensureDaily(date: LocalDate = LocalDate.now(), templateName: String? = "Daily"): String {
        val path = dailyPath(date)
        if (!io.exists(path)) {
            io.mkdir(path.substringBeforeLast('/'))
            val body = templateName?.let { applyTemplate(it, mapOf("date" to date.toString(), "title" to NotePaths.titleOf(path))) }
                ?: "---\ndate: ${date}\n---\n\n# ${NotePaths.titleOf(path)}\n\n"
            io.write(path, body)
            index.setNote(path, body)
        }
        return path
    }

    fun templates(): List<String> {
        val folder = config.root.trimEnd('/') + "/" + config.templatesFolder.trim('/')
        return io.listMarkdown(folder)
    }

    fun applyTemplate(templateTitleOrPath: String, vars: Map<String, String>): String {
        val path = if (templateTitleOrPath.endsWith(".md")) templateTitleOrPath
        else templates().firstOrNull { NotePaths.titleOf(it).equals(templateTitleOrPath, true) }
            ?: return ""
        var text = runCatching { io.read(path) }.getOrDefault("")
        for ((k, v) in vars) text = text.replace("{{$k}}", v)
        return text
    }

    fun newNote(title: String, template: String? = null): String {
        val safe = title.replace(Regex("""[\\/:*?"<>|]"""), "-").trim().ifBlank { "Untitled" }
        val path = config.root.trimEnd('/') + "/$safe.md"
        if (!io.exists(path)) {
            val body = template?.let { applyTemplate(it, mapOf("title" to safe, "date" to LocalDate.now().toString())) }
                ?: "---\ntitle: \"$safe\"\n---\n\n# $safe\n\n"
            io.write(path, body)
            index.setNote(path, body)
        }
        return path
    }

    /** Full-text search over note bodies (simple contains). The app may also use FileIndex. */
    fun search(query: String, limit: Int = 50): List<Pair<String, String>> {
        if (query.isBlank()) return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        for (p in io.listMarkdown(config.root)) {
            val body = runCatching { io.read(p) }.getOrDefault("")
            if (body.contains(query, ignoreCase = true) || NotePaths.titleOf(p).contains(query, ignoreCase = true)) {
                val line = body.lineSequence().firstOrNull { it.contains(query, ignoreCase = true) } ?: NotePaths.titleOf(p)
                out += p to line.trim()
                if (out.size >= limit) break
            }
        }
        return out
    }

    /** All note bodies (path -> text). Call off the main thread. */
    fun readAll(): Map<String, String> = io.listMarkdown(config.root).associateWith { runCatching { io.read(it) }.getOrDefault("") }

    /**
     * Rename [path] to [newTitle] (same folder) and rewrite every wikilink that pointed to it,
     * Obsidian-style. Throws if the target exists or the rename fails.
     */
    fun renameNote(path: String, newTitle: String): RenameResult {
        val clean = newTitle.trim()
        require(clean.isNotEmpty() && clean.none { it in "/\\:*?\"<>|[]#^" }) { "Invalid note title" }
        val oldTitle = NotePaths.titleOf(path)
        val dir = path.substringBeforeLast('/', "")
        val newPath = if (dir.isEmpty()) "$clean.md" else "$dir/$clean.md"
        if (newPath == path) return RenameResult(path, emptyList(), 0)
        if (!newPath.equals(path, ignoreCase = true)) require(!io.exists(newPath)) { "A note named $clean already exists" }
        val linkers = index.backlinks(path).map { it.fromPath }.distinct()
        check(io.rename(path, newPath)) { "Rename not supported for this vault" }
        index.removeNote(path)
        index.setNote(newPath, runCatching { io.read(newPath) }.getOrDefault(""))
        val updated = mutableListOf<String>()
        var total = 0
        for (p in linkers) {
            val src = if (p == path) newPath else p
            val (nb, n) = NoteOps.rewriteLinks(io.read(src), oldTitle, clean)
            if (n > 0) { io.write(src, nb); index.setNote(src, nb); updated += src; total += n }
        }
        return RenameResult(newPath, updated, total)
    }
}
