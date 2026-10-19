package ai.victorious.victorfiles.core.apk

import java.io.File
import java.util.zip.ZipFile

data class SetDiff(val added: List<String>, val removed: List<String>) {
    val isEmpty: Boolean get() = added.isEmpty() && removed.isEmpty()

    companion object {
        fun of(left: Collection<String>, right: Collection<String>): SetDiff {
            val l = left.toSet(); val r = right.toSet()
            return SetDiff(added = (r - l).sorted(), removed = (l - r).sorted())
        }
    }
}

data class FieldChange(val field: String, val left: String?, val right: String?) {
    val changed: Boolean get() = left != right
}

data class EntryChange(val name: String, val leftSize: Long, val rightSize: Long)

data class ClassChange(val descriptor: String, val methodsAdded: List<String>, val methodsRemoved: List<String>, val fieldDelta: Int)

/** Side-by-side diff of two APKs: manifest, permissions, DEX classes/methods, zip entries/resources. */
data class ApkComparison(
    val leftName: String,
    val rightName: String,
    val manifest: List<FieldChange>,
    val permissions: SetDiff,
    val classes: SetDiff,
    val changedClasses: List<ClassChange>,
    val leftClassCount: Int,
    val rightClassCount: Int,
    val entries: SetDiff,
    val changedEntries: List<EntryChange>,
    /** Non-fatal problems, e.g. a DEX that could not be parsed. */
    val warnings: List<String>,
) {
    val identical: Boolean
        get() = manifest.none { it.changed } && permissions.isEmpty && classes.isEmpty &&
            changedClasses.isEmpty() && entries.isEmpty && changedEntries.isEmpty()
}

object ApkCompare {
    private data class ClassShape(val methods: Set<String>, val fieldCount: Int)
    private data class Side(
        val manifest: ApkManifestInfo?,
        val classes: Map<String, ClassShape>,
        val entries: Map<String, Pair<Long, Long>>, // name -> (crc, size)
        val warnings: List<String>,
    )

    /** Signature-block files differ whenever the signer differs; they are reported, not hidden. */
    fun isSignatureEntry(name: String): Boolean =
        name.startsWith("META-INF/") && (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA") ||
            name.endsWith(".EC") || name == "META-INF/MANIFEST.MF")

    fun compare(left: File, right: File, maxClassChanges: Int = 500): ApkComparison {
        val l = read(left); val r = read(right)
        val lm = l.manifest; val rm = r.manifest
        val manifest = listOf(
            FieldChange("package", lm?.packageName, rm?.packageName),
            FieldChange("versionCode", lm?.versionCode?.toString(), rm?.versionCode?.toString()),
            FieldChange("versionName", lm?.versionName, rm?.versionName),
            FieldChange("minSdk", lm?.minSdk?.toString(), rm?.minSdk?.toString()),
            FieldChange("targetSdk", lm?.targetSdk?.toString(), rm?.targetSdk?.toString()),
        )
        val common = l.classes.keys.intersect(r.classes.keys).sorted()
        val changed = common.mapNotNull { d ->
            val a = l.classes.getValue(d); val b = r.classes.getValue(d)
            if (a == b) null
            else ClassChange(d, (b.methods - a.methods).sorted(), (a.methods - b.methods).sorted(), b.fieldCount - a.fieldCount)
        }.take(maxClassChanges)
        val commonEntries = l.entries.keys.intersect(r.entries.keys).sorted()
        val changedEntries = commonEntries.filter { l.entries[it]!!.first != r.entries[it]!!.first }
            .map { EntryChange(it, l.entries[it]!!.second, r.entries[it]!!.second) }
        return ApkComparison(
            leftName = left.name,
            rightName = right.name,
            manifest = manifest,
            permissions = SetDiff.of(lm?.permissions.orEmpty(), rm?.permissions.orEmpty()),
            classes = SetDiff.of(l.classes.keys, r.classes.keys),
            changedClasses = changed,
            leftClassCount = l.classes.size,
            rightClassCount = r.classes.size,
            entries = SetDiff.of(l.entries.keys, r.entries.keys),
            changedEntries = changedEntries,
            warnings = l.warnings.map { "${left.name}: $it" } + r.warnings.map { "${right.name}: $it" },
        )
    }

    private fun read(apk: File): Side {
        val warnings = mutableListOf<String>()
        val manifest = runCatching { AxmlParser.fromApk(apk) }.onFailure { warnings += "manifest: ${it.message}" }.getOrNull()
        val entries = ZipFile(apk).use { z ->
            z.entries().asSequence().filter { !it.isDirectory }.associate { it.name to (it.crc to it.size) }
        }
        val classes = HashMap<String, ClassShape>()
        for (src in runCatching { DexTools.listDex(apk) }.getOrDefault(emptyList())) {
            runCatching {
                val dex = DexTools.load(src)
                for (c in dex.classes) {
                    classes[c.type] = ClassShape(
                        methods = c.methods.map { it.name + DexTools.methodDescriptor(it) }.toSet(),
                        fieldCount = c.fields.count(),
                    )
                }
            }.onFailure { warnings += "${src.displayName}: ${it.message}" }
        }
        return Side(manifest, classes, entries, warnings)
    }

    /** Plain-text report for sharing / saving next to the APKs. */
    fun report(c: ApkComparison): String = buildString {
        appendLine("APK comparison: ${c.leftName}  →  ${c.rightName}")
        if (c.identical) appendLine("No differences in manifest, permissions, classes or entries.")
        appendLine()
        appendLine("[Manifest]")
        c.manifest.forEach { appendLine("  ${if (it.changed) "*" else " "} ${it.field}: ${it.left ?: "—"} → ${it.right ?: "—"}") }
        appendLine("[Permissions] +${c.permissions.added.size} −${c.permissions.removed.size}")
        c.permissions.added.forEach { appendLine("  + $it") }
        c.permissions.removed.forEach { appendLine("  − $it") }
        appendLine("[DEX classes] ${c.leftClassCount} → ${c.rightClassCount} (+${c.classes.added.size} −${c.classes.removed.size}, ${c.changedClasses.size} changed)")
        c.classes.added.take(200).forEach { appendLine("  + ${DexTools.descriptorToJava(it)}") }
        c.classes.removed.take(200).forEach { appendLine("  − ${DexTools.descriptorToJava(it)}") }
        c.changedClasses.take(200).forEach { ch ->
            appendLine("  ~ ${DexTools.descriptorToJava(ch.descriptor)} (methods +${ch.methodsAdded.size} −${ch.methodsRemoved.size}, fields ${"%+d".format(ch.fieldDelta)})")
        }
        appendLine("[Entries / resources] +${c.entries.added.size} −${c.entries.removed.size} ~${c.changedEntries.size}")
        c.entries.added.forEach { appendLine("  + $it") }
        c.entries.removed.forEach { appendLine("  − $it") }
        c.changedEntries.forEach { appendLine("  ~ ${it.name} (${it.leftSize} → ${it.rightSize} B)${if (isSignatureEntry(it.name)) " [signature]" else ""}") }
        if (c.warnings.isNotEmpty()) { appendLine("[Warnings]"); c.warnings.forEach { appendLine("  ! $it") } }
    }
}
