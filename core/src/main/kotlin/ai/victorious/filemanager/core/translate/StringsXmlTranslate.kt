package ai.victorious.filemanager.core.translate

import java.io.File

/**
 * Translation mode for decoded resources: res/values/strings.xml vs res/values-<locale>/strings.xml.
 * Values are kept as raw inner XML so markup (e.g. <xliff:g>, <b>) and escapes survive untouched.
 * Only <string> entries are handled; <plurals> and <string-array> are left as they are.
 */
object StringsXmlTranslate {
    data class Entry(val name: String, val value: String, val translatable: Boolean = true)
    data class Row(val name: String, val source: String, val target: String?)
    data class ImportResult(val values: Map<String, String>, val skipped: List<String>)

    private val stringRe = Regex("<string\\s+([^>]*?)(/>|>([\\s\\S]*?)</string>)")
    private val nameRe = Regex("name\\s*=\\s*\"([^\"]+)\"")
    private val translatableRe = Regex("translatable\\s*=\\s*\"false\"")

    fun parse(xml: String): List<Entry> = stringRe.findAll(xml).mapNotNull { m ->
        val attrs = m.groupValues[1]
        val name = nameRe.find(attrs)?.groupValues?.get(1) ?: return@mapNotNull null
        Entry(name, m.groups[3]?.value ?: "", !translatableRe.containsMatchIn(attrs))
    }.toList()

    /** Rows for every translatable source string, with the target value when present. */
    fun sideBySide(source: List<Entry>, target: List<Entry>): List<Row> {
        val t = target.associate { it.name to it.value }
        return source.filter { it.translatable }.map { Row(it.name, it.value, t[it.name]) }
    }

    fun missing(rows: List<Row>): List<Row> = rows.filter { it.target.isNullOrEmpty() }

    /** Writes a strings.xml containing [values] in source order; unknown names are dropped. */
    fun write(source: List<Entry>, values: Map<String, String>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n")
        for (e in source) {
            if (!e.translatable) continue
            val v = values[e.name] ?: continue
            if (v.isEmpty()) continue
            append("    <string name=\"").append(e.name).append("\">").append(escapeIfPlain(v)).append("</string>\n")
        }
        append("</resources>\n")
    }

    /** Text typed by a user may contain bare & < ' which would break the XML or aapt; markup is kept. */
    fun escapeIfPlain(v: String): String {
        var s = v.replace(Regex("&(?!(amp|lt|gt|quot|apos|#\\d+|#x[0-9a-fA-F]+);)"), "&amp;")
        if (!Regex("</?[a-zA-Z][\\w:-]*[^<>]*>").containsMatchIn(s)) s = s.replace("<", "&lt;")
        s = s.replace(Regex("(?<!\\\\)'"), "\\\\'")
        return s
    }

    fun exportCsv(rows: List<Row>): String = buildString {
        append("name,source,target\n")
        for (r in rows) append(csv(r.name)).append(',').append(csv(r.source)).append(',').append(csv(r.target ?: "")).append('\n')
    }

    fun importCsv(csv: String, source: List<Entry>): ImportResult {
        val known = source.filter { it.translatable }.map { it.name }.toSet()
        val out = LinkedHashMap<String, String>(); val skipped = ArrayList<String>()
        val records = parseCsv(csv)
        for ((i, rec) in records.withIndex()) {
            if (i == 0 && rec.firstOrNull() == "name") continue
            if (rec.size < 3) { if (rec.any { it.isNotBlank() }) skipped += "line ${i + 1}: needs name,source,target"; continue }
            if (rec[0] !in known) { skipped += "unknown name ${rec[0]}"; continue }
            if (rec[2].isNotEmpty()) out[rec[0]] = rec[2]
        }
        return ImportResult(out, skipped)
    }

    /** Default output path: <res>/values-<locale>/strings.xml next to the source's values folder. */
    fun targetFile(sourceStrings: File, locale: String): File {
        val res = sourceStrings.parentFile?.parentFile ?: sourceStrings.parentFile
        return File(File(res, "values-${locale.trim()}"), "strings.xml")
    }

    private fun csv(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    /** RFC-4180 style: quoted fields may span lines. */
    fun parseCsv(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>(); var row = ArrayList<String>(); val f = StringBuilder()
        var q = false; var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                q && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { f.append('"'); i++ }
                c == '"' -> q = !q
                !q && c == ',' -> { row += f.toString(); f.clear() }
                !q && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row += f.toString(); f.clear(); rows += row; row = ArrayList()
                }
                else -> f.append(c)
            }
            i++
        }
        if (f.isNotEmpty() || row.isNotEmpty()) { row += f.toString(); rows += row }
        return rows
    }
}
