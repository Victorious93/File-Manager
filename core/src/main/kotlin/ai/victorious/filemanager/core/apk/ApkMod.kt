package ai.victorious.filemanager.core.apk

import java.io.File
import java.util.zip.ZipFile

/** Everything a one-tap mod may change. Empty parts are skipped. */
data class ModPlan(
    val manifest: ManifestEdit = ManifestEdit(),
    val strings: Map<ArscEditor.ResTarget, String> = emptyMap(),
    /** Smali source per class, assembled into the dex that already holds that class (or classes.dex). */
    val smali: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = manifest.isEmpty && strings.isEmpty() && smali.isEmpty()
}

data class ModResult(
    val unsigned: File,
    val signed: File?,
    val verify: VerifyResult?,
    val changedEntries: List<String>,
    val summary: ManifestSummary,
)

/**
 * One-tap "APK mod": apply [ModPlan] edits inside a sealed APK, then optionally sign and verify.
 *
 * Signing never uses a bundled release key. Callers pass a [SigningKey] they loaded or generated
 * (File-Manager debug keystore, or a user-chosen keystore).
 */
object ApkMod {
    fun apply(
        apk: File,
        plan: ModPlan,
        workDir: File,
        key: SigningKey? = null,
        schemes: SignSchemes = SignSchemes(),
        alias: String = ApkSigning.DEBUG_ALIAS,
    ): ModResult {
        require(!plan.isEmpty) { "Nothing to change" }
        workDir.mkdirs()
        val replacements = LinkedHashMap<String, ByteArray>()

        if (!plan.manifest.isEmpty) {
            val raw = ApkResources.read(apk, "AndroidManifest.xml")
            replacements["AndroidManifest.xml"] = ManifestEditor.apply(raw, plan.manifest)
        }
        if (plan.strings.isNotEmpty()) {
            val arsc = Arsc.loadFromTarget(apk)
            replacements["resources.arsc"] = ArscEditor.apply(arsc, plan.strings)
        }
        if (plan.smali.isNotEmpty()) {
            val assembled = plan.smali.map { SmaliAssembler.assembleClass(it) }
            // Group classes by the dex they already live in (or classes.dex if new).
            val dexBytes = DexTools.listDex(apk).associate { src -> src.displayName to src.readBytes() }.toMutableMap()
            val defaultDex = "classes.dex"
            for (cls in assembled) {
                val host = dexBytes.entries.firstOrNull { (_, bytes) ->
                    DexTools.load(bytes).classes.any { it.type == cls.type }
                }?.key ?: defaultDex
                val current = dexBytes[host] ?: error("APK has no $host to hold ${cls.type}")
                dexBytes[host] = SmaliAssembler.replaceClass(current, cls)
                replacements[host] = dexBytes[host]!!
            }
        }

        val unsigned = File(workDir, apk.nameWithoutExtension + "_mod.apk")
        ApkBuilder.rewrite(apk, unsigned, replacements)
        val summaryBytes = replacements["AndroidManifest.xml"] ?: ApkResources.read(unsigned, "AndroidManifest.xml")
        val summary = ManifestEditor.summary(summaryBytes)

        var signed: File? = null
        var verify: VerifyResult? = null
        if (key != null) {
            signed = File(workDir, apk.nameWithoutExtension + "_mod_signed.apk")
            ApkSigning.sign(unsigned, signed, key, alias = alias, schemes = schemes, fallbackMinSdk = summary.minSdk)
            verify = ApkSigning.verify(signed, minSdk = summary.minSdk)
            require(verify.verified) { "Signed APK failed verification: ${verify.errors}" }
        }
        return ModResult(unsigned, signed, verify, replacements.keys.toList(), summary)
    }

    /** Convenience: write [out] as a signed APK (or unsigned when [key] is null). */
    fun applyTo(
        apk: File,
        plan: ModPlan,
        out: File,
        key: SigningKey?,
        schemes: SignSchemes = SignSchemes(),
        alias: String = ApkSigning.DEBUG_ALIAS,
    ): ModResult {
        val work = File(out.absoluteFile.parentFile, ".fm-mod-${System.nanoTime()}")
        try {
            val r = apply(apk, plan, work, key, schemes, alias)
            val src = r.signed ?: r.unsigned
            src.copyTo(out, overwrite = true)
            return r.copy(signed = if (key != null) out else null, unsigned = if (key == null) out else r.unsigned)
        } finally {
            work.deleteRecursively()
        }
    }

    fun hasEntry(apk: File, name: String): Boolean =
        ZipFile(apk).use { it.getEntry(name) != null }
}
