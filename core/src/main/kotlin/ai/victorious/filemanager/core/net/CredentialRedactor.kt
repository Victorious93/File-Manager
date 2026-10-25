package ai.victorious.filemanager.core.net

object CredentialRedactor {
    // The password may itself contain '@' ("p@ssw0rd" is common), so run to the LAST '@' before the path.
    private val PASS_IN_URI = Regex("""(://[^:/@\s]+:)([^/\s]+)(@)""")
    private val AUTH_HEADER = Regex("""(?i)(authorization\s*[:=]\s*)((?:bearer|basic|digest|token)\s+)?([^\s,"']+)""")
    private val PASSWORD_FIELD = Regex(
        """(?i)("?(?:password|passwd|passphrase|pwd|secret|secret[_-]?key|private[_-]?key|api[_-]?key|access[_-]?key|token)"?\s*[:=]\s*)("[^"]*"|'[^']*'|[^,"'\s&;]+)""",
    )

    fun redact(text: String): String {
        var out = PASS_IN_URI.replace(text) { "${it.groupValues[1]}***${it.groupValues[3]}" }
        out = AUTH_HEADER.replace(out) { "${it.groupValues[1]}${it.groupValues[2]}***" }
        out = PASSWORD_FIELD.replace(out) { m ->
            val quote = m.groupValues[2].first().takeIf { it == '"' || it == '\'' }
            m.groupValues[1] + if (quote != null) "$quote***$quote" else "***"
        }
        return out
    }

    fun safeMessage(t: Throwable): String = redact(t.message ?: t::class.simpleName ?: "error")
}
