package ai.victorious.filemanager.core.ai

import ai.victorious.filemanager.core.diff.DiffOp
import ai.victorious.filemanager.core.diff.LineDiff
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** What the user asked Droidcommand AI to do with the code. */
enum class AssistTask(val wire: String) { EXPLAIN("explain"), SUGGEST_EDIT("suggest_edit") }

/** One request from VictorFiles to Droidcommand AI (contract v1, see docs/PHASE17.md). */
data class AssistRequest(
    val task: AssistTask,
    val fileName: String,
    val language: String,
    val code: String,
    val instruction: String = "",
    val truncated: Boolean = false,
)

/** Droidcommand AI's answer. [revisedCode] is the full new file for SUGGEST_EDIT. */
data class AssistResult(val ok: Boolean, val explanation: String?, val revisedCode: String?, val error: String?)

/**
 * Wire contract between VictorFiles (client) and Droidcommand AI (provider).
 * Transport is an explicit Activity intent with JSON extras; no API key lives in VictorFiles.
 */
object CodeAssistContract {
    const val VERSION = 1
    const val PROVIDER_PACKAGE = "ai.droidcommand.app"
    const val ACTION = "ai.droidcommand.action.CODE_ASSIST"
    const val EXTRA_REQUEST = "ai.droidcommand.extra.REQUEST_JSON"
    const val EXTRA_RESULT = "ai.droidcommand.extra.RESULT_JSON"

    /** Binder transactions cap near 1 MB; keep the code payload well below it. */
    const val MAX_CODE_CHARS = 60_000

    private val json = Json { ignoreUnknownKeys = true }

    fun request(task: AssistTask, fileName: String, language: String, code: String, instruction: String = ""): AssistRequest {
        val cut = code.length > MAX_CODE_CHARS
        return AssistRequest(task, fileName, language, if (cut) code.take(MAX_CODE_CHARS) else code, instruction.trim(), cut)
    }

    fun encode(r: AssistRequest): String = buildJsonObject {
        put("v", VERSION)
        put("task", r.task.wire)
        put("fileName", r.fileName)
        put("language", r.language)
        put("code", r.code)
        put("instruction", r.instruction)
        put("truncated", r.truncated)
        put("prompt", PromptBuilder.build(r))
    }.toString()

    /** Parses a provider result. Never throws; malformed input becomes ok=false. */
    fun decode(raw: String?): AssistResult {
        if (raw.isNullOrBlank()) return AssistResult(false, null, null, "Droidcommand AI returned no result")
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return AssistResult(false, null, null, "Droidcommand AI returned invalid JSON")
        val v = (obj["v"] as? JsonPrimitive)?.intOrNull ?: VERSION
        if (v > VERSION) return AssistResult(false, null, null, "Unsupported contract version $v; update VictorFiles")
        val ok = (obj["ok"] as? JsonPrimitive)?.booleanOrNull ?: false
        val explanation = obj.str("explanation")
        var revised = obj.str("revisedCode")
        if (revised == null && explanation != null) revised = extractFencedCode(explanation)
        return if (ok) AssistResult(true, explanation, revised, null)
        else AssistResult(false, null, null, obj.str("error") ?: "Droidcommand AI reported an error")
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    /** Returns the body of the largest ``` fenced block, or null. */
    fun extractFencedCode(text: String): String? {
        val re = Regex("```[^\\n`]*\\n([\\s\\S]*?)```")
        return re.findAll(text).map { it.groupValues[1] }.maxByOrNull { it.length }
    }
}

/** Builds the natural-language prompt sent along with the structured fields. */
object PromptBuilder {
    fun build(r: AssistRequest): String = buildString {
        val lang = r.language.lowercase()
        when (r.task) {
            AssistTask.EXPLAIN -> append("Explain what this ").append(lang).append(" code from ").append(r.fileName)
                .append(" does. Be concise; call out registers, control flow and API calls")
                .append(if (lang == "smali") " (this is Dalvik smali bytecode)." else ".")
            AssistTask.SUGGEST_EDIT -> {
                append("Edit this ").append(lang).append(" file (").append(r.fileName).append(").\n")
                append("Instruction: ").append(r.instruction.ifBlank { "Fix bugs and improve clarity without changing behaviour." })
                append("\nReturn the COMPLETE revised file in a single fenced code block, then a short explanation.")
                if (lang == "smali") append(" Keep .locals/.registers counts valid and do not rename classes.")
            }
        }
        if (r.truncated) append("\nNote: the file was truncated to the first ${r.code.length} characters.")
        append("\n\n```").append(lang).append('\n').append(r.code)
        if (!r.code.endsWith("\n")) append('\n')
        append("```")
    }
}

/** A contiguous change between original and proposed text. Line indices are 0-based into the original. */
data class EditHunk(val id: Int, val oldStart: Int, val removed: List<String>, val added: List<String>)

/** Splits a suggested edit into hunks and applies only the accepted ones. */
object EditHunks {
    fun hunks(original: String, proposed: String): List<EditHunk> {
        val out = ArrayList<EditHunk>()
        val d = LineDiff.diff(original, proposed)
        var oldIdx = 0
        var i = 0
        while (i < d.size) {
            if (d[i].op == DiffOp.EQUAL) { oldIdx++; i++; continue }
            val start = oldIdx
            val rem = ArrayList<String>(); val add = ArrayList<String>()
            while (i < d.size && d[i].op != DiffOp.EQUAL) {
                if (d[i].op == DiffOp.DELETE) { rem += d[i].text; oldIdx++ } else add += d[i].text
                i++
            }
            out += EditHunk(out.size, start, rem, add)
        }
        return out
    }

    /** Applies hunks whose id is in [accepted] to [original]. Keeps the original trailing newline. */
    fun apply(original: String, hunks: List<EditHunk>, accepted: Set<Int>): String {
        val lines = LineDiff.lines(original)
        val out = ArrayList<String>()
        var idx = 0
        for (h in hunks.sortedBy { it.oldStart }) {
            while (idx < h.oldStart) out += lines[idx++]
            if (h.id in accepted) { out += h.added; idx += h.removed.size }
        }
        while (idx < lines.size) out += lines[idx++]
        val nl = if (original.contains("\r\n")) "\r\n" else "\n"
        val trailing = original.isEmpty() || original.endsWith("\n")
        return out.joinToString(nl) + if (trailing && out.isNotEmpty()) nl else ""
    }
}
