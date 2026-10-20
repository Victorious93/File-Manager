package ai.victorious.victorfiles.core.patch

import ai.victorious.victorfiles.core.apk.ApkMod
import ai.victorious.victorfiles.core.apk.ApkResources
import ai.victorious.victorfiles.core.apk.ApkSigning
import ai.victorious.victorfiles.core.apk.AxmlDecoder
import ai.victorious.victorfiles.core.apk.DexTools
import ai.victorious.victorfiles.core.apk.ManifestEdit
import ai.victorious.victorfiles.core.apk.ManifestEditor
import ai.victorious.victorfiles.core.apk.ModPlan
import ai.victorious.victorfiles.core.apk.ModResult
import ai.victorious.victorfiles.core.apk.SignSchemes
import ai.victorious.victorfiles.core.apk.SigningKey
import ai.victorious.victorfiles.core.diff.LineDiff
import java.io.File

/** The static patch presets. Every one is applied to resources/smali and re-signed; none hooks a process. */
enum class ManifestFlag { DEBUGGABLE, ALLOW_BACKUP, CLEARTEXT_TRAFFIC, EXTRACT_NATIVE_LIBS }

/** One requested patch. Sealed so preview and apply share exactly one list of steps. */
sealed interface PatchStep {
    /** Set an <application> boolean flag. */
    data class SetManifestFlag(val flag: ManifestFlag, val enabled: Boolean) : PatchStep

    /** Force a signature/license-style method to return a constant (pattern-chosen by the user from [SmaliPatch.detect]). */
    data class NeutralizeMethod(val classType: String, val signature: String, val boolValue: Boolean = true) : PatchStep

    /** Inject a `Log.d(tag, message)` at method entry (debugging aid). */
    data class InjectEntryLog(val classType: String, val signature: String, val tag: String, val message: String) : PatchStep

    /** Remove every `Toast.show()` call in a class. */
    data class RemoveToasts(val classType: String) : PatchStep

    /**
     * Add a network-security-config that trusts user-installed CAs. DEFERRED: the current static
     * ARSC tooling cannot add a new res/xml resource + table entry (see docs/APK_DEEP_EDIT.md), so
     * apply() refuses this step. Use [SetManifestFlag] CLEARTEXT_TRAFFIC for plain-HTTP needs.
     */
    data object TrustUserCertificates : PatchStep
}

/** A previewed change to one entry inside the APK. */
data class FileChange(
    val entry: String,
    val summary: String,
    /** Unified diff of the human-readable form (decoded manifest XML, or disassembled smali). */
    val diff: String,
    /** False when the step cannot be applied statically (e.g. [PatchStep.TrustUserCertificates]). */
    val applicable: Boolean = true,
)

data class PatchApplied(val result: ModResult, val output: File, val backup: File) {
    /** Undo: restore the original APK bytes over the output. */
    fun undo() { backup.copyTo(output, overwrite = true) }
}

/**
 * A patch session bound to one APK and a [PatchGate]. Nothing is written until [apply], and apply
 * refuses unless the user has authorized this exact package+cert in the gate (no bypass).
 */
class PatchSession(private val apk: File, private val gate: PatchGate) {

    val manifestBytes: ByteArray = ApkResources.read(apk, "AndroidManifest.xml")
    val summary = ManifestEditor.summary(manifestBytes)
    val packageName: String get() = summary.packageName

    /** SHA-256 of the current signing cert (uppercase colon form), or "UNSIGNED". */
    val certSha256: String by lazy {
        val signers = ApkSigning.verify(apk, minSdk = summary.minSdk).signers
        signers.firstOrNull()?.sha256?.let { PatchGate.normalize(it) } ?: "UNSIGNED"
    }

    fun isAuthorized(): Boolean = gate.isAuthorized(packageName, certSha256)

    /** Builds a preview (diff per changed entry) without writing anything. */
    fun preview(steps: List<PatchStep>): List<FileChange> {
        val changes = mutableListOf<FileChange>()
        val manifestEdit = manifestEdit(steps)
        if (!manifestEdit.isEmpty) {
            val after = ManifestEditor.apply(manifestBytes, manifestEdit)
            changes += FileChange(
                "AndroidManifest.xml",
                "manifest flags",
                LineDiff.unified(LineDiff.diff(AxmlDecoder.toXml(manifestBytes), AxmlDecoder.toXml(after))),
            )
        }
        for ((cls, smali) in smaliChanges(steps)) {
            val before = originalSmali(cls)
            changes += FileChange(
                dexEntryFor(cls) ?: "classes.dex",
                "smali: ${DexTools.descriptorToJava(cls)}",
                LineDiff.unified(LineDiff.diff(before, smali)),
            )
        }
        if (steps.any { it is PatchStep.TrustUserCertificates }) {
            changes += FileChange(
                "res/xml/network_security_config.xml",
                "trust user CAs (deferred)",
                "# Deferred: adding a new res/xml resource needs an ARSC table entry the static tooling " +
                    "cannot add yet. Use the 'usesCleartextTraffic' preset for plain-HTTP, or add the " +
                    "network-security-config resource in a full build.",
                applicable = false,
            )
        }
        return changes
    }

    /**
     * Applies [steps]: verifies gate authorization, rewrites manifest + smali, then signs and
     * verifies with [key] (never a bundled release key). Writes to [out]; a backup of the original
     * is kept so the returned [PatchApplied.undo] can restore it.
     */
    fun apply(
        steps: List<PatchStep>,
        out: File,
        key: SigningKey?,
        schemes: SignSchemes = SignSchemes(),
        alias: String = ApkSigning.DEBUG_ALIAS,
        workDir: File = File(out.absoluteFile.parentFile, ".fm-patch-${System.nanoTime()}"),
    ): PatchApplied {
        gate.requireAuthorized(packageName, certSha256)
        require(steps.none { it is PatchStep.TrustUserCertificates }) {
            "TrustUserCertificates is deferred (cannot add an ARSC resource statically); remove it."
        }
        require(steps.isNotEmpty()) { "No patch steps selected" }

        val manifestEdit = manifestEdit(steps)
        val smali = smaliChanges(steps).values.toList()
        val plan = ModPlan(manifest = manifestEdit, smali = smali)
        require(!plan.isEmpty) { "Nothing to change" }

        val backup = File(out.absoluteFile.parentFile, apk.nameWithoutExtension + ".orig.apk")
        apk.copyTo(backup, overwrite = true)
        workDir.mkdirs()
        try {
            val result = ApkMod.applyTo(apk, plan, out, key, schemes, alias)
            return PatchApplied(result, out, backup)
        } finally {
            workDir.deleteRecursively()
        }
    }

    // --- internals ---

    private fun manifestEdit(steps: List<PatchStep>): ManifestEdit {
        var e = ManifestEdit()
        for (s in steps) if (s is PatchStep.SetManifestFlag) e = when (s.flag) {
            ManifestFlag.DEBUGGABLE -> e.copy(debuggable = s.enabled)
            ManifestFlag.ALLOW_BACKUP -> e.copy(allowBackup = s.enabled)
            ManifestFlag.CLEARTEXT_TRAFFIC -> e.copy(usesCleartextTraffic = s.enabled)
            ManifestFlag.EXTRACT_NATIVE_LIBS -> e.copy(extractNativeLibs = s.enabled)
        }
        return e
    }

    /** Final smali text per touched class, applying all of its steps in order. */
    private fun smaliChanges(steps: List<PatchStep>): Map<String, String> {
        val byClass = LinkedHashMap<String, MutableList<PatchStep>>()
        for (s in steps) {
            val cls = when (s) {
                is PatchStep.NeutralizeMethod -> s.classType
                is PatchStep.InjectEntryLog -> s.classType
                is PatchStep.RemoveToasts -> s.classType
                else -> null
            } ?: continue
            byClass.getOrPut(cls) { mutableListOf() }.add(s)
        }
        val out = LinkedHashMap<String, String>()
        for ((cls, clsSteps) in byClass) {
            var smali = originalSmali(cls)
            for (s in clsSteps) smali = when (s) {
                is PatchStep.NeutralizeMethod -> SmaliPatch.neutralizeMethod(smali, s.signature, s.boolValue)
                is PatchStep.InjectEntryLog -> SmaliPatch.injectEntryLog(smali, s.signature, s.tag, s.message)
                is PatchStep.RemoveToasts -> SmaliPatch.removeToasts(smali).first
                else -> smali
            }
            out[cls] = smali
        }
        return out
    }

    private val dexCache = HashMap<String, String?>()

    private fun dexEntryFor(classType: String): String? = dexCache.getOrPut(classType) {
        DexTools.listDex(apk).firstOrNull { src -> DexTools.load(src).classes.any { it.type == classType } }?.displayName
    }

    private fun originalSmali(classType: String): String {
        for (src in DexTools.listDex(apk)) {
            val dex = DexTools.load(src)
            val cls = dex.classes.firstOrNull { it.type == classType }
            if (cls != null) return DexTools.classSmali(cls)
        }
        throw IllegalArgumentException("Class not found in APK: $classType")
    }
}
