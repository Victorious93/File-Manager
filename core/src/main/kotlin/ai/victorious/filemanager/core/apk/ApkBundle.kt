package ai.victorious.filemanager.core.apk

import java.io.File
import java.util.zip.ZipFile

/**
 * Split APK / APKS / XAPK support.
 *
 * Limits (documented for users):
 * - Merging produces a single "universal" APK by concatenating non-conflicting entries from
 *   base + config splits. Conflicting paths keep the first occurrence (base wins).
 * - ABI/density/language splits that only differ by native libs / resources are merged; if two
 *   splits both ship the same path with different contents, only the first is kept.
 * - Signing is stripped (META-INF) — the merged APK must be re-signed.
 * - Feature modules that require dynamic delivery are listed but not installed as separate
 *   sessions; install uses one multi-package PackageInstaller session for all APKs in the set.
 * - Encrypted / obfuscated commercial APKS formats beyond the zip-of-apks layout are not supported.
 */
data class SplitApk(val file: File, val name: String, val size: Long, val kind: SplitKind) {
    val isBase: Boolean get() = kind == SplitKind.BASE
}

enum class SplitKind { BASE, CONFIG, FEATURE, UNKNOWN }

data class ApkBundle(
    val source: File,
    val format: BundleFormat,
    val packageName: String?,
    val splits: List<SplitApk>,
    /** Temp dir holding extracted APKs when [source] is a zip bundle; null for a folder of APKs. */
    val extractDir: File?,
) {
    val base: SplitApk? get() = splits.firstOrNull { it.isBase } ?: splits.firstOrNull()
}

enum class BundleFormat { APK_SET, APKS, XAPK, FOLDER }

object ApkBundles {
    fun isBundleName(name: String): Boolean {
        val e = name.substringAfterLast('.', "").lowercase()
        return e == "apks" || e == "xapk" || e == "apkm"
    }

    fun open(file: File, workDir: File = File(file.parentFile, ".bundle_${file.nameWithoutExtension}")): ApkBundle {
        return when {
            file.isDirectory -> fromFolder(file)
            isBundleName(file.name) || looksLikeZipOfApks(file) -> fromZip(file, workDir)
            else -> throw IllegalArgumentException("Not an APK bundle: ${file.name}")
        }
    }

    fun fromFolder(dir: File): ApkBundle {
        val apks = (dir.listFiles() ?: emptyArray()).filter { it.isFile && it.extension.equals("apk", true) }
            .sortedBy { it.name }
        require(apks.isNotEmpty()) { "No APKs in ${dir.path}" }
        return ApkBundle(dir, BundleFormat.FOLDER, packageNameOf(apks), apks.map(::classify), null)
    }

    private fun fromZip(file: File, workDir: File): ApkBundle {
        workDir.mkdirs()
        val apks = ArrayList<File>()
        ZipFile(file).use { z ->
            for (e in z.entries()) {
                if (e.isDirectory || !e.name.endsWith(".apk", true)) continue
                val out = File(workDir, File(e.name).name)
                z.getInputStream(e).use { input -> out.outputStream().use { input.copyTo(it) } }
                apks += out
            }
        }
        require(apks.isNotEmpty()) { "No APKs inside ${file.name}" }
        val fmt = when (file.extension.lowercase()) {
            "xapk" -> BundleFormat.XAPK
            "apks", "apkm" -> BundleFormat.APKS
            else -> BundleFormat.APK_SET
        }
        return ApkBundle(file, fmt, packageNameOf(apks), apks.sortedBy { it.name }.map(::classify), workDir)
    }

    private fun looksLikeZipOfApks(file: File): Boolean =
        runCatching {
            ZipFile(file).use { z -> z.entries().asSequence().any { !it.isDirectory && it.name.endsWith(".apk", true) } }
        }.getOrDefault(false)

    private fun packageNameOf(apks: List<File>): String? =
        apks.asSequence().mapNotNull { runCatching { AxmlParser.fromApk(it).packageName }.getOrNull() }.firstOrNull()

    private fun classify(f: File): SplitApk {
        val n = f.name.lowercase()
        val kind = when {
            n == "base.apk" || n.endsWith("-base.apk") || n.startsWith("base-") -> SplitKind.BASE
            n.contains("config.") || n.contains(".config.") || n.contains("-armeabi") ||
                n.contains("-arm64") || n.contains("-x86") || n.contains("-xxxhdpi") ||
                n.contains("-xxhdpi") || n.contains("-xhdpi") || n.contains("-hdpi") ||
                n.contains("-mdpi") || n.contains("-ldpi") || n.contains("-tvdpi") ||
                Regex("""-[a-z]{2}\.apk$""").containsMatchIn(n) -> SplitKind.CONFIG
            n.contains("feature") || n.contains("module") -> SplitKind.FEATURE
            else -> if (apksIsProbablyBase(f)) SplitKind.BASE else SplitKind.UNKNOWN
        }
        return SplitApk(f, f.name, f.length(), kind)
    }

    private fun apksIsProbablyBase(f: File): Boolean =
        runCatching { ZipFile(f).use { it.getEntry("resources.arsc") != null && it.getEntry("AndroidManifest.xml") != null } }.getOrDefault(false)

    /**
     * Merges [splits] into a single APK at [out]. First occurrence of each path wins (base
     * should be listed first). Signature files are dropped.
     * @return number of entries written.
     */
    fun merge(splits: List<File>, out: File): Int {
        require(splits.isNotEmpty()) { "No splits to merge" }
        val seen = LinkedHashSet<String>()
        var count = 0
        // Materialize entries then rebuild via ApkBuilder-compatible zip with alignment.
        val staging = File(out.parentFile, out.nameWithoutExtension + "_merge_staging").apply {
            deleteRecursively(); mkdirs()
        }
        try {
            for (apk in splits) {
                ZipFile(apk).use { z ->
                    for (e in z.entries()) {
                        if (e.isDirectory) continue
                        val name = e.name.trimStart('/')
                        if (ApkBuilder.isSignatureFile(name) || !seen.add(name)) continue
                        val target = File(staging, name)
                        target.parentFile?.mkdirs()
                        z.getInputStream(e).use { input -> target.outputStream().use { input.copyTo(it) } }
                        count++
                    }
                }
            }
            ApkBuilder.rebuild(staging, out, dropSignatures = true)
        } finally {
            staging.deleteRecursively()
        }
        return count
    }
}
