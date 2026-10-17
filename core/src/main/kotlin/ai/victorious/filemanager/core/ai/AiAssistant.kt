package ai.victorious.filemanager.core.ai

import ai.victorious.filemanager.core.diff.DiffLine
import ai.victorious.filemanager.core.diff.LineDiff
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

enum class AiMode { EXPLAIN, SUGGEST_EDIT }

/** User-configured OpenAI-compatible endpoint. The API key is passed separately and never stored here. */
data class AiEndpoint(val baseUrl: String, val model: String) {
    init {
        require(model.isNotBlank()) { "Model is required" }
    }

    /** `https://host/v1` + `/chat/completions`, tolerating a trailing slash or a full URL. */
    val chatCompletionsUrl: String
        get() {
            val b = baseUrl.trim().trimEnd('/')
            require(b.startsWith("https://") || b.startsWith("http://")) { "Base URL must start with http(s)://" }
            return if (b.endsWith("/chat/completions")) b else "$b/chat/completions"
        }

    val host: String get() = baseUrl.trim().substringAfter("://").substringBefore('/').substringBefore('?')
}

data class AiMessage(val role: String, val content: String)

data class AiPrompt(val messages: List<AiMessage>, val redactions: Int, val truncated: Boolean)

/**
 * Strips likely secrets from code before it leaves the device. Conservative: it is a
 * safety net, not a guarantee, so the UI still asks the user to confirm every send.
 */
object SecretRedactor {
    const val MASK = "«redacted»"

    private val patterns = listOf(
        // PEM private keys
        Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"),
        // OpenAI-style / GitHub / AWS / Google / Slack tokens
        Regex("\\bsk-[A-Za-z0-9_-]{16,}"),
        Regex("\\bgh[pousr]_[A-Za-z0-9]{20,}"),
        Regex("\\bAKIA[0-9A-Z]{16}\\b"),
        Regex("\\bAIza[0-9A-Za-z_-]{35}\\b"),
        Regex("\\bxox[baprs]-[A-Za-z0-9-]{10,}"),
        // Bearer headers
        Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{12,}"),
    )

    // key = "value" / key: value for secret-ish names
    private val assignment = Regex(
        "(?i)((?:api[_-]?key|secret|password|passwd|pwd|token|access[_-]?key|private[_-]?key|client[_-]?secret)\\s*[\"']?\\s*[:=]\\s*[\"']?)([^\"'\\s,;]{4,})",
    )

    fun redact(text: String): Pair<String, Int> {
        var count = 0
        var out = text
        for (p in patterns) {
            out = p.replace(out) { m ->
                count++
                if (m.groupValues.size > 1 && m.groupValues[1].isNotEmpty() && m.value.startsWith(m.groupValues[1])) {
                    m.groupValues[1] + MASK
                } else {
                    MASK
                }
            }
        }
        out = assignment.replace(out) { m ->
            count++
            m.groupValues[1] + MASK
        }
        return out to count
    }

    /** For logs/errors: also hides the configured key wherever it appears. */
    fun scrub(text: String, apiKey: String?): String {
        var t = text
        if (!apiKey.isNullOrBlank() && apiKey.length >= 4) t = t.replace(apiKey, MASK)
        return redact(t).first
    }
}

object PromptBuilder {
    const val MAX_CODE_CHARS = 24_000

    fun build(mode: AiMode, fileName: String, language: String, code: String, instruction: String = ""): AiPrompt {
        val (redacted, n) = SecretRedactor.redact(code)
        val truncated = redacted.length > MAX_CODE_CHARS
        val body = if (truncated) redacted.take(MAX_CODE_CHARS) else redacted
        val system = when (mode) {
            AiMode.EXPLAIN ->
                "You are a code assistant inside an Android file manager. Explain the user's $language code " +
                    "concisely: what it does, notable risks or bugs. Do not invent context you cannot see."
            AiMode.SUGGEST_EDIT ->
                "You are a code assistant inside an Android file manager. Return an improved version of the " +
                    "user's $language snippet. Reply with ONE fenced code block containing the full replacement " +
                    "snippet, then at most three short bullet points explaining the change. Keep the same scope; " +
                    "do not add unrelated code."
        }
        val user = buildString {
            append("File: ").append(fileName).append('\n')
            if (instruction.isNotBlank()) append("Request: ").append(instruction.trim()).append('\n')
            if (truncated) append("(Snippet truncated to $MAX_CODE_CHARS characters.)\n")
            append("```").append(language.lowercase()).append('\n')
            append(body)
            if (!body.endsWith('\n')) append('\n')
            append("```")
        }
        return AiPrompt(listOf(AiMessage("system", system), AiMessage("user", user)), n, truncated)
    }

    fun requestJson(endpoint: AiEndpoint, prompt: AiPrompt, temperature: Double = 0.2): String {
        val obj = buildJsonObject {
            put("model", endpoint.model)
            put("temperature", temperature)
            put("messages", buildJsonArray {
                for (m in prompt.messages) add(buildJsonObject { put("role", m.role); put("content", m.content) })
            })
        }
        return obj.toString()
    }
}

sealed interface AiResult {
    data class Explanation(val text: String) : AiResult
    data class Edit(val replacement: String, val notes: String, val diff: List<DiffLine>) : AiResult
    data class Failure(val message: String) : AiResult
}

object AiResponseParser {
    private val json = Json { ignoreUnknownKeys = true }
    private val fence = Regex("```[A-Za-z0-9_+.-]*[ \\t]*\\r?\\n([\\s\\S]*?)\\r?\\n?```")

    /** Pulls `choices[0].message.content` out of an OpenAI-compatible response, or an API error message. */
    fun content(responseBody: String): Result<String> = runCatching {
        val root = json.parseToJsonElement(responseBody).jsonObject
        (root["error"] as? JsonObject)?.let { err ->
            val msg = (err["message"] as? JsonPrimitive)?.contentOrNull ?: "API error"
            throw IllegalStateException(msg)
        }
        val choices = root["choices"] as? JsonArray ?: throw IllegalStateException("No choices in response")
        val first = choices.firstOrNull()?.jsonObject ?: throw IllegalStateException("Empty choices")
        val msg = first["message"]?.jsonObject ?: throw IllegalStateException("No message in response")
        msg["content"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Empty content")
    }

    fun extractCode(content: String): String? =
        fence.find(content)?.groupValues?.get(1)?.trimEnd('\r', '\n')

    fun notesOutsideCode(content: String): String = fence.replace(content, "").trim()

    fun toResult(mode: AiMode, original: String, content: String): AiResult = when (mode) {
        AiMode.EXPLAIN -> AiResult.Explanation(content.trim())
        AiMode.SUGGEST_EDIT -> {
            val code = extractCode(content)
            if (code == null) {
                AiResult.Failure("The model did not return a code block; nothing to apply.")
            } else {
                AiResult.Edit(code, notesOutsideCode(content), LineDiff.diff(original, code))
            }
        }
    }

    /** Applies an accepted replacement to [full] text over the selected range. */
    fun applyEdit(full: String, selection: IntRange?, replacement: String): String {
        if (selection == null || selection.isEmpty()) return replacement
        val start = selection.first.coerceIn(0, full.length)
        val endExclusive = (selection.last + 1).coerceIn(start, full.length)
        return full.substring(0, start) + replacement + full.substring(endExclusive)
    }
}
