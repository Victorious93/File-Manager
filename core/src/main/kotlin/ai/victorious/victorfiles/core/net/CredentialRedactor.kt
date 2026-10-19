package ai.victorious.victorfiles.core.net

object CredentialRedactor {
    private val PASS_IN_URI = Regex("""(://[^:/@]+:)([^@/]+)(@)""")
    private val PASSWORD_FIELD = Regex("""(?i)("?(?:password|passwd|secret|private[_-]?key|token)"?\s*[:=]\s*)("?)([^,"\s]+)("?)""")

    fun redact(text: String): String {
        var out = PASS_IN_URI.replace(text) { "${it.groupValues[1]}***${it.groupValues[3]}" }
        out = PASSWORD_FIELD.replace(out) { "${it.groupValues[1]}${it.groupValues[2]}***${it.groupValues[4]}" }
        return out
    }

    fun safeMessage(t: Throwable): String = redact(t.message ?: t::class.simpleName ?: "error")
}
