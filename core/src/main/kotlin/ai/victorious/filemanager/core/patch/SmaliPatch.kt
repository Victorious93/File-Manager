package ai.victorious.filemanager.core.patch

import ai.victorious.filemanager.core.apk.SmaliText

/**
 * Pure, text-level smali transforms used by the static patch presets. Nothing here hooks a running
 * process; every change is applied to smali, re-assembled to DEX, and re-signed. Detection is
 * pattern-based on generic API/name signals - it carries no list of specific apps or vendors.
 */
object SmaliPatch {

    /** A method flagged as a likely signature- or license-check, with the generic signals that matched. */
    data class MethodMatch(
        val classType: String,
        val signature: String,
        val header: String,
        val returnType: String,
        val reasons: List<String>,
    )

    enum class Kind { SIGNATURE, LICENSE }

    // Generic API fingerprints - framework types/members, not app-specific.
    private val SIGNATURE_API = listOf(
        "Landroid/content/pm/Signature;",
        "Landroid/content/pm/SigningInfo;",
        "getPackageInfo",
        "GET_SIGNATURES",
        "GET_SIGNING_CERTIFICATES",
        "signingInfo",
        "getApkContentsSigners",
        "getSigningCertificateHistory",
    )
    private val LICENSE_API = listOf(
        "LicenseChecker",
        "LicenseValidator",
        "Lcom/android/vending/licensing",
        "checkAccess",
        "ILicensingService",
        "Policy;->allowAccess",
    )
    private val NAME_HINTS = listOf(
        "verifysig", "checksig", "signaturematch", "signaturevalid", "validatesignature",
        "checkcert", "verifycert", "checkintegrity", "verifyintegrity", "tamper",
        "checklicense", "verifylicense", "islicensed", "checkpremium", "ispro", "ispaid",
        "isgenuine", "ispurchased", "checksubscription",
    )

    /**
     * Scans a disassembled class for methods that look like signature- or license-verification,
     * using only generic API and naming signals. Returns candidates; the caller previews and chooses.
     */
    fun detect(classType: String, classSmali: String, kinds: Set<Kind> = setOf(Kind.SIGNATURE, Kind.LICENSE)): List<MethodMatch> {
        val out = mutableListOf<MethodMatch>()
        for (m in SmaliText.methods(classSmali)) {
            val ret = returnType(m.signature)
            val lower = m.signature.substringBefore('(').lowercase()
            val body = m.text
            val reasons = mutableListOf<String>()
            var kindMatch = false
            if (Kind.SIGNATURE in kinds) {
                val apiHits = SIGNATURE_API.filter { body.contains(it) }
                if (apiHits.isNotEmpty()) { reasons += "signature API: ${apiHits.joinToString()}"; kindMatch = true }
            }
            if (Kind.LICENSE in kinds) {
                val apiHits = LICENSE_API.filter { body.contains(it) }
                if (apiHits.isNotEmpty()) { reasons += "license API: ${apiHits.joinToString()}"; kindMatch = true }
            }
            val nameHits = NAME_HINTS.filter { lower.contains(it) }
            if (nameHits.isNotEmpty()) reasons += "name hint: ${nameHits.joinToString()}"
            // Flag when an API signal is present, or a name hint on a boolean-returning method.
            val flag = kindMatch || (nameHits.isNotEmpty() && ret == "Z")
            if (flag) out += MethodMatch(classType, m.signature, m.header.trim(), ret, reasons)
        }
        return out
    }

    /** The return descriptor of a `name(params)ret` signature. */
    fun returnType(signature: String): String = signature.substringAfterLast(')')

    /**
     * Replaces a method's whole body with a minimal `return <constant>` of the right type.
     * [boolValue] is used only for `Z`-returning methods (true neutralizes a failing check).
     * Throws if the method is abstract/native (no body) or not found.
     */
    fun neutralizeMethod(classSmali: String, signature: String, boolValue: Boolean = true): String {
        val block = SmaliText.method(classSmali, signature)
            ?: throw IllegalArgumentException("Method not found: $signature")
        val header = block.lineSequence().first { it.trimStart().startsWith(".method ") }
        require(!header.contains(" abstract ") && !header.contains(" native ")) {
            "Cannot neutralize an abstract/native method: $signature"
        }
        val ret = returnType(signature)
        val (locals, bodyLines) = constReturn(ret, boolValue)
        val replacement = buildString {
            append(header.trimEnd()).append('\n')
            append("    .locals ").append(locals).append('\n')
            bodyLines.forEach { append("    ").append(it).append('\n') }
            append(".end method")
        }
        return classSmali.replace(block, replacement)
    }

    private fun constReturn(ret: String, boolValue: Boolean): Pair<Int, List<String>> = when {
        ret == "V" -> 0 to listOf("return-void")
        ret == "Z" -> 1 to listOf("const/4 v0, ${if (boolValue) "0x1" else "0x0"}", "return v0")
        ret in setOf("B", "C", "S", "I") -> 1 to listOf("const/4 v0, 0x0", "return v0")
        ret == "J" -> 2 to listOf("const-wide/16 v0, 0x0", "return-wide v0")
        ret == "F" -> 1 to listOf("const/4 v0, 0x0", "return v0")
        ret == "D" -> 2 to listOf("const-wide/16 v0, 0x0", "return-wide v0")
        else -> 1 to listOf("const/4 v0, 0x0", "return-object v0") // object or array
    }

    /**
     * Removes every `Landroid/widget/Toast;->show()V` invocation in the class (leaving any Toast
     * object construction unused and inert). Register-safe: it only deletes invoke lines, which
     * define no registers. Returns the new smali and the number of removed calls.
     */
    fun removeToasts(classSmali: String): Pair<String, Int> {
        var removed = 0
        val kept = classSmali.lines().filterNot { line ->
            val t = line.trim()
            val hit = t.startsWith("invoke-") && t.contains("Landroid/widget/Toast;->show()V")
            if (hit) removed++
            hit
        }
        return kept.joinToString("\n") to removed
    }

    /**
     * Injects a `Landroid/util/Log;->d(tag, message)I` call at the entry of [signature], without
     * disturbing existing registers. The method is first put in `.locals` form (so parameters are
     * referenced as p-registers and adding locals never shifts them), then two fresh locals hold
     * the tag and message. Throws if the method has no body.
     */
    fun injectEntryLog(classSmali: String, signature: String, tag: String, message: String): String {
        val block = SmaliText.method(classSmali, signature)
            ?: throw IllegalArgumentException("Method not found: $signature")
        val local = toLocalsForm(block, signature)
        val lines = local.lines().toMutableList()
        val dirIdx = lines.indexOfFirst { it.trim().startsWith(".locals ") }
        require(dirIdx >= 0) { "No .locals directive after normalization" }
        val n = lines[dirIdx].trim().removePrefix(".locals ").trim().toInt()
        lines[dirIdx] = lines[dirIdx].substringBefore(".locals") + ".locals " + (n + 2)
        val tagReg = "v$n"
        val msgReg = "v${n + 1}"
        val inject = listOf(
            "    const-string $tagReg, \"${escape(tag)}\"",
            "    const-string $msgReg, \"${escape(message)}\"",
            "    invoke-static {$tagReg, $msgReg}, Landroid/util/Log;->d(Ljava/lang/String;Ljava/lang/String;)I",
        )
        lines.addAll(dirIdx + 1, inject)
        val rebuilt = lines.joinToString("\n")
        return classSmali.replace(block, rebuilt)
    }

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    /**
     * Returns the method text with a `.locals` directive (converting a `.registers` directive and
     * rewriting its parameter registers to p-notation). Idempotent when already in `.locals` form.
     */
    fun toLocalsForm(methodText: String, signature: String): String {
        val lines = methodText.lines().toMutableList()
        val regIdx = lines.indexOfFirst { it.trim().startsWith(".registers ") }
        if (regIdx < 0) return methodText // already .locals form (or bodiless)
        val total = lines[regIdx].trim().removePrefix(".registers ").trim().toInt()
        val header = lines.first { it.trimStart().startsWith(".method ") }
        val isStatic = header.contains(" static ")
        val paramWidths = parseParamWidths(signature)
        val paramWords = paramWidths.sum() + if (isStatic) 0 else 1
        val locals = total - paramWords
        require(locals >= 0) { "Register count underflow in $signature" }
        // In .locals form, parameter register p_k == v(locals + k) (p-index is a register offset,
        // so wide params simply occupy two consecutive p-registers). A plain offset rewrite is exact.
        lines[regIdx] = lines[regIdx].substringBefore(".registers") + ".locals $locals"
        for (i in lines.indices) {
            if (i == regIdx) continue
            lines[i] = remapRegisters(lines[i], locals)
        }
        return lines.joinToString("\n")
    }

    /** Widths (1, or 2 for J/D) of each parameter in a `name(params)ret` signature. */
    fun parseParamWidths(signature: String): List<Int> {
        val params = signature.substringAfter('(').substringBefore(')')
        val widths = mutableListOf<Int>()
        var i = 0
        while (i < params.length) {
            when (params[i]) {
                'J', 'D' -> { widths += 2; i++ }
                'L' -> { widths += 1; i = params.indexOf(';', i) + 1 }
                '[' -> { // array: skip all '[' then one type (object or primitive)
                    while (i < params.length && params[i] == '[') i++
                    if (i < params.length && params[i] == 'L') i = params.indexOf(';', i) + 1 else i++
                    widths += 1
                }
                else -> { widths += 1; i++ } // Z B C S I F V
            }
        }
        return widths
    }

    /** Rewrites parameter v-registers to p-notation on one line, ignoring any quoted string literal. */
    private fun remapRegisters(line: String, locals: Int): String {
        val q = line.indexOf('"')
        val code = if (q >= 0) line.substring(0, q) else line
        val tail = if (q >= 0) line.substring(q) else ""
        val rewritten = Regex("""\bv(\d+)\b""").replace(code) { m ->
            val idx = m.groupValues[1].toInt()
            if (idx >= locals) "p${idx - locals}" else m.value
        }
        return rewritten + tail
    }
}
