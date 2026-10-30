package ai.victorious.filemanager.core.appmgr

import ai.victorious.filemanager.core.priv.PrivilegeAvailability
import ai.victorious.filemanager.core.priv.PrivilegeBackend
import ai.victorious.filemanager.core.priv.PrivilegeSelector
import ai.victorious.filemanager.core.priv.ShizukuState
import ai.victorious.filemanager.core.shell.ShellCommands
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.zip.ZipFile

/*
 * App Manager+ (written from scratch). Features were chosen from the public README of
 * MuntashirAkon/AppManager (GPL-3); none of its code was read or copied.
 */

/** Reads every class name out of an APK's classes*.dex files, so tracker/library scans see real code, not just manifest components. */
object DexClassLister {
    /** Lists class names from [apks] (base + splits). Stops after [maxClasses] to bound memory on huge apps. */
    fun classes(apks: List<File>, maxClasses: Int = 400_000): List<String> {
        val out = LinkedHashSet<String>()
        for (apk in apks) {
            if (!apk.canRead()) continue
            runCatching {
                ZipFile(apk).use { zip ->
                    val dexes = zip.entries().asSequence().filter { DEX_NAME.matches(it.name) }.toList()
                    for (e in dexes) {
                        val bytes = zip.getInputStream(e).use { it.readBytes() }
                        out += fromDex(bytes)
                        if (out.size >= maxClasses) return out.take(maxClasses)
                    }
                }
            }
        }
        return out.toList()
    }

    fun fromDex(bytes: ByteArray): List<String> =
        runCatching { DexBackedDexFile(Opcodes.getDefault(), bytes).classes.map { TrackerScanner.descriptorToClass(it.type) } }
            .getOrDefault(emptyList())

    private val DEX_NAME = Regex("""^classes\d*\.dex$""")
}

/** A well-known (non-tracking) library, detected by its public package prefix. */
data class LibrarySignature(val name: String, val kind: String, val prefixes: List<String>)
data class LibraryHit(val library: LibrarySignature, val classCount: Int)

/** Self-built list from each project's public package names (facts, not copied from any dataset). */
object LibrarySignatures {
    val ALL = listOf(
        LibrarySignature("Kotlin stdlib", "Language", listOf("kotlin.")),
        LibrarySignature("Kotlin coroutines", "Language", listOf("kotlinx.coroutines.")),
        LibrarySignature("Jetpack Compose", "UI", listOf("androidx.compose.")),
        LibrarySignature("AndroidX AppCompat", "UI", listOf("androidx.appcompat.")),
        LibrarySignature("Material Components", "UI", listOf("com.google.android.material.")),
        LibrarySignature("AndroidX Room", "Database", listOf("androidx.room.")),
        LibrarySignature("SQLCipher", "Database", listOf("net.sqlcipher.", "net.zetetic.database.")),
        LibrarySignature("Realm", "Database", listOf("io.realm.")),
        LibrarySignature("OkHttp", "Network", listOf("okhttp3.")),
        LibrarySignature("Retrofit", "Network", listOf("retrofit2.")),
        LibrarySignature("Volley", "Network", listOf("com.android.volley.")),
        LibrarySignature("Ktor", "Network", listOf("io.ktor.")),
        LibrarySignature("gRPC", "Network", listOf("io.grpc.")),
        LibrarySignature("Gson", "Serialization", listOf("com.google.gson.")),
        LibrarySignature("Moshi", "Serialization", listOf("com.squareup.moshi.")),
        LibrarySignature("Jackson", "Serialization", listOf("com.fasterxml.jackson.")),
        LibrarySignature("Protocol Buffers", "Serialization", listOf("com.google.protobuf.")),
        LibrarySignature("Glide", "Images", listOf("com.bumptech.glide.")),
        LibrarySignature("Coil", "Images", listOf("coil.", "coil3.")),
        LibrarySignature("Picasso", "Images", listOf("com.squareup.picasso.")),
        LibrarySignature("Fresco", "Images", listOf("com.facebook.fresco.", "com.facebook.imagepipeline.")),
        LibrarySignature("ExoPlayer / Media3", "Media", listOf("com.google.android.exoplayer2.", "androidx.media3.")),
        LibrarySignature("RxJava", "Reactive", listOf("io.reactivex.")),
        LibrarySignature("Dagger / Hilt", "DI", listOf("dagger.")),
        LibrarySignature("Koin", "DI", listOf("org.koin.")),
        LibrarySignature("Google Play Services", "Platform", listOf("com.google.android.gms.common.")),
        LibrarySignature("Google Play Billing", "Payments", listOf("com.android.billingclient.")),
        LibrarySignature("Stripe", "Payments", listOf("com.stripe.android.")),
        LibrarySignature("BouncyCastle", "Crypto", listOf("org.bouncycastle.")),
        LibrarySignature("Tink", "Crypto", listOf("com.google.crypto.tink.")),
        LibrarySignature("React Native", "Framework", listOf("com.facebook.react.")),
        LibrarySignature("Flutter", "Framework", listOf("io.flutter.")),
        LibrarySignature("Xamarin / .NET", "Framework", listOf("mono.android.", "crc64")),
        LibrarySignature("Unity", "Framework", listOf("com.unity3d.player.")),
        LibrarySignature("Apache Cordova", "Framework", listOf("org.apache.cordova.")),
        LibrarySignature("ML Kit", "AI", listOf("com.google.mlkit.")),
        LibrarySignature("TensorFlow Lite", "AI", listOf("org.tensorflow.lite.")),
    )

    fun scan(classNames: Collection<String>, sigs: List<LibrarySignature> = ALL): List<LibraryHit> =
        sigs.mapNotNull { s ->
            val n = classNames.count { c -> s.prefixes.any { c.startsWith(it) } }
            if (n == 0) null else LibraryHit(s, n)
        }.sortedByDescending { it.classCount }
}

/**
 * Optional Exodus Privacy tracker list (https://reports.exodus-privacy.eu.org/api/trackers).
 * That data is under the Open Database License (ODbL 1.0) with contents under DbCL 1.0. It is
 * NOT bundled: the user downloads it on demand and it is cached as a separate file, credited in the UI.
 */
object ExodusTrackers {
    const val URL = "https://reports.exodus-privacy.eu.org/api/trackers"
    const val ATTRIBUTION = "Tracker data © Exodus Privacy, ODbL 1.0 (contents DbCL 1.0)"
    private val json = Json { ignoreUnknownKeys = true }
    private val META = Regex("""[\[\]()*+?^$\\{}]""")

    data class Parsed(val signatures: List<TrackerSignature>, val skippedPatterns: Int)

    fun parse(text: String): Parsed {
        val root = json.parseToJsonElement(text).jsonObject
        val trackers = root["trackers"]?.jsonObject ?: return Parsed(emptyList(), 0)
        var skipped = 0
        val sigs = trackers.values.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = o["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val code = o["code_signature"]?.jsonPrimitive?.content.orEmpty()
            val prefixes = code.split('|').map { it.trim().replace("\\.", ".") }.filter { it.isNotEmpty() }.filter {
                val bad = META.containsMatchIn(it) || it.length < 4 || !it.contains('.')
                if (bad) skipped++
                !bad
            }
            if (prefixes.isEmpty()) return@mapNotNull null
            val cats = o["categories"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            TrackerSignature(name, category(cats), prefixes)
        }
        return Parsed(sigs, skipped)
    }

    fun category(cats: List<String>): TrackerSignature.Category {
        val c = cats.joinToString(" ").lowercase()
        return when {
            "advert" in c || "ads" in c -> TrackerSignature.Category.ADVERTISING
            "crash" in c -> TrackerSignature.Category.CRASH_REPORTING
            "location" in c -> TrackerSignature.Category.LOCATION
            "profil" in c -> TrackerSignature.Category.PROFILING
            "identif" in c -> TrackerSignature.Category.ATTRIBUTION
            else -> TrackerSignature.Category.ANALYTICS
        }
    }

    /** Built-in list first; Exodus entries add trackers we don't already name. */
    fun merge(builtIn: List<TrackerSignature>, exodus: List<TrackerSignature>): List<TrackerSignature> {
        val names = builtIn.map { it.name.lowercase() }.toHashSet()
        val prefixes = builtIn.flatMap { it.prefixes }.toHashSet()
        return builtIn + exodus.filter { e -> e.name.lowercase() !in names && e.prefixes.none { it in prefixes } }
    }
}

/** Options for a privileged `pm install` (root/Shizuku). Normal installs keep using the system dialog. */
data class InstallOptions(
    val replace: Boolean = true,
    val downgrade: Boolean = false,
    val testOnly: Boolean = false,
    val grantAllPermissions: Boolean = false,
    val installerPackage: String? = null,
    val user: Int? = null,
    val bypassLowTargetSdk: Boolean = false,
)

object InstallCommands {
    private val PKG = Regex("""^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$""")

    fun validInstaller(p: String) = PKG.matches(p)

    fun flags(o: InstallOptions, sdk: Int): List<String> {
        val f = mutableListOf<String>()
        if (o.replace) f += "-r"
        if (o.downgrade) f += "-d"
        if (o.testOnly) f += "-t"
        if (o.grantAllPermissions) f += "-g"
        o.installerPackage?.takeIf { it.isNotBlank() }?.let {
            require(validInstaller(it)) { "Installer must be a package name like com.android.vending" }
            f += listOf("-i", it)
        }
        o.user?.let { f += listOf("--user", it.toString()) }
        if (o.bypassLowTargetSdk && sdk >= 34) f += "--bypass-low-target-sdk-block"
        return f
    }

    /**
     * Copies the APK to /data/local/tmp (readable by the shell user under both root and Shizuku),
     * installs it, then removes the temp copy. The cleanup runs even if the install fails.
     */
    fun plan(apkPath: String, o: InstallOptions, sdk: Int, stamp: Long): List<String> {
        require(apkPath.endsWith(".apk", ignoreCase = true)) { "Only single .apk files; use the normal installer for split bundles" }
        val tmp = "/data/local/tmp/fm_install_$stamp.apk"
        val q = ShellCommands.quote(tmp)
        return listOf(
            "cp ${ShellCommands.quote(apkPath)} $q",
            (listOf("pm", "install") + flags(o, sdk) + q).joinToString(" "),
            "rm -f $q",
        )
    }
}

/** More multi-app actions on top of [BatchOps]. Each returns shell commands for a confirm-then-run plan. */
object BatchOpsPlus {
    fun unfreeze(pkgs: List<String>, user: Int = 0) = BatchPlan("Unfreeze apps", pkgs, pkgs.map { AppState.unsuspend(it, user) })
    fun clearData(pkgs: List<String>, user: Int = 0) = BatchPlan("Clear data", pkgs, pkgs.map { AppState.clearData(it, user) })

    /** Root deletes cache dirs directly; Shizuku uses `pm clear --cache-only` (Android 14+). */
    fun clearCache(pkgs: List<String>, backend: PrivilegeBackend, sdk: Int, user: Int = 0): BatchPlan {
        val cmds = when {
            backend == PrivilegeBackend.ROOT -> pkgs.map { p ->
                require(p.matches(Regex("""^[A-Za-z0-9_.]+$"""))) { "Bad package name: $p" }
                val q = p
                "rm -rf /data/user/$user/$q/cache/* /data/user/$user/$q/code_cache/* /data/user_de/$user/$q/cache/* /data/user_de/$user/$q/code_cache/*"
            }
            backend == PrivilegeBackend.SHIZUKU && sdk >= 34 -> pkgs.map { "pm clear --cache-only --user $user ${ShellCommands.quote(it)}" }
            backend == PrivilegeBackend.SHIZUKU -> throw IllegalStateException("Clearing one app's cache via Shizuku needs Android 14+. Use Root, or clear it in system settings.")
            else -> throw IllegalStateException("Clear cache needs Root or Shizuku")
        }
        return BatchPlan("Clear cache", pkgs, cmds)
    }

    /** `pm uninstall --user` works for user apps and removes system apps for this user only (restorable with `cmd package install-existing`). */
    fun uninstall(pkgs: List<String>, keepData: Boolean = false, user: Int = 0) = BatchPlan(
        if (keepData) "Uninstall (keep data)" else "Uninstall",
        pkgs,
        pkgs.map { "pm uninstall ${if (keepData) "-k " else ""}--user $user ${ShellCommands.quote(it)}" },
    )

    fun reinstallExisting(pkgs: List<String>, user: Int = 0) =
        BatchPlan("Restore system apps for user", pkgs, pkgs.map { "cmd package install-existing --user $user ${ShellCommands.quote(it)}" })

    fun resetAppOps(pkgs: List<String>) = BatchPlan("Reset app ops", pkgs, pkgs.map(AppOpsManagerOps::reset))
}

/** Common app-op names, so the editor can add an op the app hasn't used yet. */
object AppOpCatalog {
    val COMMON = listOf(
        "CAMERA", "RECORD_AUDIO", "COARSE_LOCATION", "FINE_LOCATION", "MONITOR_LOCATION",
        "READ_CONTACTS", "WRITE_CONTACTS", "READ_CALENDAR", "WRITE_CALENDAR", "READ_SMS", "SEND_SMS",
        "READ_CLIPBOARD", "WRITE_CLIPBOARD", "POST_NOTIFICATION", "SYSTEM_ALERT_WINDOW", "WRITE_SETTINGS",
        "RUN_IN_BACKGROUND", "RUN_ANY_IN_BACKGROUND", "WAKE_LOCK", "GET_USAGE_STATS", "REQUEST_INSTALL_PACKAGES",
        "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "MANAGE_EXTERNAL_STORAGE", "READ_MEDIA_IMAGES",
        "READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO", "BODY_SENSORS", "ACTIVITY_RECOGNITION", "PICTURE_IN_PICTURE",
        "SCHEDULE_EXACT_ALARM", "BLUETOOTH_SCAN", "NEARBY_WIFI_DEVICES", "VIBRATE",
    )
    private val NAME = Regex("""^[A-Z][A-Z0-9_]{1,63}$""")
    fun valid(op: String) = NAME.matches(op)
}

/** Per-app mobile/Wi-Fi traffic (from NetworkStatsManager buckets). */
data class NetUsage(val uid: Int, val mobileRx: Long = 0, val mobileTx: Long = 0, val wifiRx: Long = 0, val wifiTx: Long = 0) {
    val mobile get() = mobileRx + mobileTx
    val wifi get() = wifiRx + wifiTx
    val total get() = mobile + wifi
}

object NetUsageAggregator {
    fun merge(rows: List<NetUsage>): List<NetUsage> =
        rows.groupBy { it.uid }.map { (uid, l) ->
            NetUsage(uid, l.sumOf { it.mobileRx }, l.sumOf { it.mobileTx }, l.sumOf { it.wifiRx }, l.sumOf { it.wifiTx })
        }.filter { it.total > 0 }.sortedByDescending { it.total }

    fun formatBytes(b: Long): String {
        if (b < 1024) return "$b B"
        val units = listOf("KB", "MB", "GB", "TB")
        var v = b / 1024.0
        var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return if (v >= 100) "%.0f %s".format(v, units[i]) else "%.1f %s".format(v, units[i])
    }
}

/** What the App Manager can do right now, shown as one clear banner. */
object PrivilegeStatus {
    enum class Level { ROOT, SHIZUKU, LIMITED }
    data class Banner(val level: Level, val title: String, val detail: String, val action: String?)

    fun banner(a: PrivilegeAvailability, shizuku: ShizukuState): Banner = when (PrivilegeSelector.select(a)) {
        PrivilegeBackend.ROOT -> Banner(Level.ROOT, "Root: ready", "All actions are available, including component blocking with Intent Firewall, cache clearing and data backup.", null)
        PrivilegeBackend.SHIZUKU -> Banner(Level.SHIZUKU, "Shizuku: ready", "Freeze, permissions, app ops, uninstall and installer options work. Data backup and Intent Firewall need root.", null)
        PrivilegeBackend.NORMAL -> {
            val why = PrivilegeSelector.missingReason(a)
            val action = when {
                a.shizukuEnabled && shizuku == ShizukuState.DENIED -> "Grant Shizuku"
                !a.rootEnabled && !a.shizukuEnabled -> "Open settings"
                else -> null
            }
            Banner(Level.LIMITED, "Limited mode", "Viewing, scanning and usage stats work. Changing apps needs Root or Shizuku. ${why ?: ""}".trim(), action)
        }
    }

    enum class Need { NONE, ELEVATED, ROOT }

    /** Null when allowed; otherwise a short reason to show on a disabled button. */
    fun blockReason(need: Need, backend: PrivilegeBackend): String? = when (need) {
        Need.NONE -> null
        Need.ELEVATED -> if (backend == PrivilegeBackend.NORMAL) "Needs Root or Shizuku" else null
        Need.ROOT -> if (backend == PrivilegeBackend.ROOT) null else "Needs Root"
    }
}
