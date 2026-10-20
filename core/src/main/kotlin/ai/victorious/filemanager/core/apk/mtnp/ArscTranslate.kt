package ai.victorious.filemanager.core.apk.mtnp

import ai.victorious.filemanager.core.apk.Arsc
import ai.victorious.filemanager.core.apk.ArscEditor
import ai.victorious.filemanager.core.apk.ArscString
import java.io.StringWriter

/**
 * Translation mode for resources.arsc: side-by-side locales, export/import XML or CSV,
 * and optional AI suggestion hooks (caller supplies translations; nothing is auto-applied).
 *
 * Adding a brand-new locale that does not already exist in the ARSC type configs needs type-table
 * surgery beyond [ArscEditor] (same limitation documented in APK_DEEP_EDIT). This module:
 *  - edits values for **existing** locale configs via [ArscEditor]
 *  - exports/imports for translators
 *  - reports which locales are present and which strings are missing in a target locale
 */
object ArscTranslate {
    data class Row(
        val resId: Int,
        val name: String,
        val values: Map<String, String>, // locale -> text
    )

    data class Suggestion(val resId: Int, val name: String, val sourceLocale: String, val targetLocale: String, val sourceText: String, val suggestedText: String)

    fun locales(arsc: Arsc): List<String> = arsc.locales()

    fun sideBySide(arsc: Arsc, locales: List<String>? = null): List<Row> {
        val locs = locales ?: arsc.locales()
        return arsc.strings.groupBy { it.resId }.map { (id, list) ->
            Row(id, list.first().name, locs.associateWith { loc -> list.firstOrNull { it.locale == loc }?.value ?: "" })
        }.sortedBy { it.name }
    }

    fun missingInLocale(arsc: Arsc, targetLocale: String, sourceLocale: String = "default"): List<ArscString> {
        val byId = arsc.strings.groupBy { it.resId }
        return byId.mapNotNull { (_, list) ->
            val src = list.firstOrNull { it.locale == sourceLocale } ?: return@mapNotNull null
            if (list.none { it.locale == targetLocale }) src else null
        }
    }

    /** Export selected locales as CSV: resId,name,locale1,locale2,… */
    fun exportCsv(arsc: Arsc, locales: List<String>): String {
        val rows = sideBySide(arsc, locales)
        val sw = StringWriter()
        sw.append("resId,name,").append(locales.joinToString(",")).append('\n')
        for (r in rows) {
            sw.append("0x%08x".format(r.resId)).append(',').append(csv(r.name))
            for (loc in locales) sw.append(',').append(csv(r.values[loc].orEmpty()))
            sw.append('\n')
        }
        return sw.toString()
    }

    /** Android-style strings.xml for one locale. */
    fun exportXml(arsc: Arsc, locale: String): String {
        val sw = StringWriter()
        sw.append("""<?xml version="1.0" encoding="utf-8"?>""").append('\n')
        sw.append("<!-- locale=$locale -->\n<resources>\n")
        for (s in arsc.strings.filter { it.locale == locale }.sortedBy { it.name }) {
            sw.append("  <string name=\"").append(xmlAttr(s.name)).append("\">")
                .append(xmlText(s.value)).append("</string>\n")
        }
        sw.append("</resources>\n")
        return sw.toString()
    }

    /**
     * Import CSV produced by [exportCsv]. Returns ArscEditor targets for locales that already
     * exist in the ARSC. Unknown resIds / locales are listed in [ImportResult.skipped].
     */
    data class ImportResult(val edits: Map<ArscEditor.ResTarget, String>, val skipped: List<String>)

    fun importCsv(arsc: Arsc, csv: String): ImportResult {
        val lines = csv.lineSequence().filter { it.isNotBlank() }.toList()
        require(lines.isNotEmpty()) { "empty CSV" }
        val header = parseCsvLine(lines.first())
        require(header.size >= 3 && header[0] == "resId" && header[1] == "name") { "bad CSV header" }
        val locales = header.drop(2)
        val present = arsc.locales().toSet()
        val known = arsc.strings.associateBy { it.resId to it.locale }
        val edits = LinkedHashMap<ArscEditor.ResTarget, String>()
        val skipped = mutableListOf<String>()
        for (line in lines.drop(1)) {
            val cols = parseCsvLine(line)
            if (cols.size < 2) continue
            val id = cols[0].removePrefix("0x").toLongOrNull(16)?.toInt()
            if (id == null) { skipped += "bad id ${cols[0]}"; continue }
            for ((i, loc) in locales.withIndex()) {
                val value = cols.getOrNull(2 + i) ?: continue
                if (value.isEmpty()) continue
                if (loc !in present) { skipped += "locale $loc not in ARSC"; continue }
                if ((id to loc) !in known && known.keys.none { it.first == id }) {
                    skipped += "unknown res 0x%08x".format(id); continue
                }
                // Only edit if that locale row exists
                if (known.containsKey(id to loc) || arsc.strings.any { it.resId == id && it.locale == loc }) {
                    edits[ArscEditor.ResTarget(id, loc)] = value
                } else skipped += "no $loc entry for 0x%08x".format(id)
            }
        }
        return ImportResult(edits, skipped.distinct())
    }

    fun importXml(arsc: Arsc, locale: String, xml: String): ImportResult {
        val present = arsc.locales().toSet()
        if (locale !in present) return ImportResult(emptyMap(), listOf("locale $locale not in ARSC (cannot add new type config yet)"))
        val byName = arsc.strings.filter { it.locale == locale }.associateBy { it.name }
        val edits = LinkedHashMap<ArscEditor.ResTarget, String>()
        val skipped = mutableListOf<String>()
        val re = Regex("""<string\s+name="([^"]+)">([^<]*)</string>""")
        for (m in re.findAll(xml)) {
            val name = unescapeXml(m.groupValues[1])
            val value = unescapeXml(m.groupValues[2])
            val row = byName[name]
            if (row == null) skipped += "unknown name $name"
            else edits[ArscEditor.ResTarget(row.resId, locale)] = value
        }
        return ImportResult(edits, skipped)
    }

    /** Apply edits for existing locales; returns new ARSC bytes. */
    fun apply(arsc: Arsc, edits: Map<ArscEditor.ResTarget, String>): ByteArray = ArscEditor.apply(arsc, edits)

    /**
     * Build prompt snippets for the existing AI assistant. The UI shows these as suggestions;
     * the user must approve before [apply].
     */
    fun aiPrompt(rows: List<Row>, sourceLocale: String, targetLocale: String, limit: Int = 40): String {
        val need = rows.filter { it.values[sourceLocale].orEmpty().isNotEmpty() && it.values[targetLocale].orEmpty().isEmpty() }.take(limit)
        return buildString {
            appendLine("Translate Android string resources from $sourceLocale to $targetLocale.")
            appendLine("Return one line per item as: name<TAB>translation")
            appendLine()
            for (r in need) appendLine("${r.name}\t${r.values[sourceLocale]}")
        }
    }

    fun parseAiResponse(arsc: Arsc, targetLocale: String, response: String): List<Suggestion> {
        val byName = arsc.strings.filter { it.locale == "default" || it.locale == targetLocale }.groupBy { it.name }
        return response.lineSequence().mapNotNull { line ->
            val tab = line.indexOf('\t')
            val (name, text) = if (tab > 0) line.substring(0, tab).trim() to line.substring(tab + 1).trim()
            else {
                val sp = line.indexOf(' ')
                if (sp <= 0) return@mapNotNull null
                line.substring(0, sp).trim() to line.substring(sp + 1).trim()
            }
            if (name.isEmpty() || text.isEmpty()) return@mapNotNull null
            val src = byName[name]?.firstOrNull { it.locale == "default" } ?: byName[name]?.firstOrNull() ?: return@mapNotNull null
            Suggestion(src.resId, name, "default", targetLocale, src.value, text)
        }.toList()
    }

    private fun csv(s: String): String =
        if (s.contains(',') || s.contains('"') || s.contains('\n')) "\"" + s.replace("\"", "\"\"") + "\"" else s

    private fun parseCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i <= line.length) {
            if (i < line.length && line[i] == '"') {
                val sb = StringBuilder(); i++
                while (i < line.length) {
                    if (line[i] == '"') {
                        if (i + 1 < line.length && line[i + 1] == '"') { sb.append('"'); i += 2 }
                        else { i++; break }
                    } else sb.append(line[i++])
                }
                out += sb.toString()
                if (i < line.length && line[i] == ',') i++
            } else {
                val next = line.indexOf(',', i).let { if (it < 0) line.length else it }
                out += line.substring(i, next)
                i = next + 1
            }
        }
        return out
    }

    private fun xmlAttr(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;")
    private fun xmlText(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun unescapeXml(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")
}
