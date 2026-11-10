package ai.victorious.filemanager.core.appmgr

import ai.victorious.filemanager.core.shell.ShellCommands

/*
 * App Manager leftovers (written from scratch). Chosen from the public feature list of
 * MuntashirAkon/AppManager (GPL-3); none of its code, strings or data were read or copied.
 * No debloat list is bundled: the user imports their own.
 */

/** One APK of a split install, with its size (pm install-write needs it up front). */
data class SplitApk(val path: String, val sizeBytes: Long)

object SplitInstallCommands {
    private val SAFE_NAME = Regex("""^[A-Za-z0-9._-]{1,120}\.apk$""")

    /** Names of the .apk entries inside a .apks / .xapk bundle, base first. Rejects anything with a path. */
    fun bundleApkEntries(entryNames: List<String>): List<String> {
        val apks = entryNames.filter { n -> !n.endsWith("/") && n.endsWith(".apk", ignoreCase = true) && !n.contains("..") && !n.startsWith("/") }
        require(apks.isNotEmpty()) { "No APKs inside this bundle" }
        return apks.sortedWith(compareBy({ !it.substringAfterLast('/').equals("base.apk", true) }, { it.substringAfterLast('/') }))
    }

    /** Local file name an entry is extracted to (no folders, nothing odd). */
    fun safeName(entry: String, index: Int): String {
        val n = entry.substringAfterLast('/')
        return if (SAFE_NAME.matches(n)) n else "split_$index.apk"
    }

    /**
     * Privileged split install with the same [InstallOptions] as single APKs:
     * copy every APK into one temp folder readable by the shell user, create a session with the
     * options, write each split, commit, and abandon the session if any step fails.
     * Returns (install script, cleanup command). The cleanup runs even if the install fails.
     */
    fun plan(apks: List<SplitApk>, o: InstallOptions, sdk: Int, stamp: Long): List<String> {
        require(apks.isNotEmpty()) { "Pick at least one APK" }
        require(apks.all { it.path.endsWith(".apk", ignoreCase = true) }) { "Every file must be an .apk (extract .apks bundles first)" }
        require(apks.all { it.sizeBytes > 0 }) { "An APK is empty or unreadable" }
        val dir = "/data/local/tmp/fm_split_$stamp"
        val q = ShellCommands.quote(dir)
        val total = apks.sumOf { it.sizeBytes }
        val flags = (InstallCommands.flags(o, sdk) + listOf("-S", total.toString())).joinToString(" ")
        val steps = ArrayList<String>()
        steps += "mkdir -p $q"
        apks.forEachIndexed { i, a -> steps += "cp ${ShellCommands.quote(a.path)} $q/$i.apk" }
        steps += "sid=\$(pm install-create $flags | sed -n 's/.*\\[\\([0-9][0-9]*\\)\\].*/\\1/p')"
        steps += "[ -n \"\$sid\" ]"
        apks.forEachIndexed { i, a -> steps += "pm install-write -S ${a.sizeBytes} \$sid $i.apk $q/$i.apk" }
        steps += "pm install-commit \$sid"
        val script = steps.joinToString(" && ") + " || { [ -n \"\$sid\" ] && pm install-abandon \$sid; false; }"
        return listOf(script, "rm -rf $q")
    }
}

/** User-imported debloat list -> freeze / disable / uninstall-for-user plan. */
object Debloater {
    enum class Action(val label: String) {
        FREEZE("Freeze (suspend)"), DISABLE("Disable"), UNINSTALL_FOR_USER("Uninstall for this user (keep data)");

        fun command(pkg: String, user: Int = 0): String = when (this) {
            FREEZE -> AppState.suspend(pkg, user)
            DISABLE -> AppState.disable(pkg, user)
            UNINSTALL_FOR_USER -> "pm uninstall -k --user $user ${ShellCommands.quote(pkg)}"
        }

        /** The command that undoes [command]. */
        fun undo(pkg: String, user: Int = 0): String = when (this) {
            FREEZE -> AppState.unsuspend(pkg, user)
            DISABLE -> AppState.enable(pkg, user)
            UNINSTALL_FOR_USER -> "cmd package install-existing --user $user ${ShellCommands.quote(pkg)}"
        }
    }

    private val PKG = Regex("""^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$""")

    /** Packages we never touch, whatever the list says: removing them can stop the phone booting or leave it without settings. */
    val PROTECTED = setOf(
        "android", "com.android.systemui", "com.android.settings", "com.android.phone", "com.android.shell",
        "com.android.providers.settings", "com.android.providers.media", "com.android.providers.media.module",
        "com.android.providers.telephony", "com.android.providers.contacts", "com.android.server.telecom",
        "com.android.packageinstaller", "com.google.android.packageinstaller",
        "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.android.networkstack", "com.google.android.networkstack", "com.android.externalstorage",
        "com.android.se", "com.android.nfc", "com.android.bluetooth", "com.android.inputdevices",
        "com.android.keychain", "com.android.location.fused", "com.android.webview", "com.google.android.webview",
        "com.google.android.gms", "com.google.android.gsf", "rikka.shizuku", "moe.shizuku.privileged.api",
        "com.topjohnwu.magisk", "me.weishu.kernelsu",
    )

    data class Parsed(val packages: List<String>, val invalidLines: List<Int>, val duplicates: Int)

    /**
     * Plain text, one package per line. `#`, `//` and `;` start comments. Commas or spaces may follow the
     * package (CSV-style lists and lines like `pm uninstall --user 0 com.foo` work: the first token that
     * looks like a package name wins).
     */
    fun parse(text: String, max: Int = 5000): Parsed {
        val out = LinkedHashSet<String>()
        val invalid = ArrayList<Int>()
        var dupes = 0
        text.lineSequence().forEachIndexed { i, raw ->
            val line = raw.substringBefore('#').substringBefore("//").substringBefore(';').trim()
            if (line.isEmpty()) return@forEachIndexed
            val pkg = line.split(Regex("[\\s,\t\"']+")).firstOrNull { PKG.matches(it) && !it.startsWith("--") }
            when {
                pkg == null -> invalid += i + 1
                !out.add(pkg) -> dupes++
            }
            if (out.size >= max) return Parsed(out.toList(), invalid, dupes)
        }
        return Parsed(out.toList(), invalid, dupes)
    }

    data class Plan(
        val action: Action,
        val targets: List<String>,
        val notInstalled: List<String>,
        val protectedSkipped: List<String>,
        val batch: BatchPlan,
        val undo: BatchPlan,
    )

    /** Only packages that are installed and not protected get a command. [keep] adds the app itself, the launcher, the keyboard, … */
    fun plan(list: List<String>, installed: Set<String>, action: Action, keep: Set<String> = emptySet(), user: Int = 0): Plan {
        val guard = PROTECTED + keep
        val prot = list.filter { it in guard }
        val missing = list.filter { it !in guard && it !in installed }
        val targets = list.filter { it !in guard && it in installed }
        val batch = BatchPlan("Debloat: ${action.label}", targets, targets.map { action.command(it, user) })
        val undo = BatchPlan("Undo debloat: ${action.label}", targets, targets.map { action.undo(it, user) })
        return Plan(action, targets, missing, prot, batch, undo)
    }
}

/** Bounded buffer for the live (streaming) logcat: keeps the newest [capacity] lines. */
class LogRing(private val capacity: Int = 5000) {
    private val buf = ArrayDeque<LogLine>()
    var dropped: Long = 0
        private set

    @Synchronized fun add(line: LogLine) {
        buf.addLast(line)
        while (buf.size > capacity) { buf.removeFirst(); dropped++ }
    }

    @Synchronized fun addAll(lines: List<LogLine>) = lines.forEach(::add)
    @Synchronized fun snapshot(): List<LogLine> = buf.toList()
    @Synchronized fun clear() { buf.clear(); dropped = 0 }
    val size: Int @Synchronized get() = buf.size
}

object LiveLogcat {
    /** `logcat -v threadtime -T <backlog>`: prints the last lines, then keeps streaming new ones until stopped. */
    fun command(backlog: Int = 200, pid: Int? = null): Array<String> {
        val args = mutableListOf("logcat", "-v", "threadtime", "-T", backlog.coerceIn(1, 5000).toString())
        pid?.takeIf { it > 0 }?.let { args += "--pid=$it" }
        return args.toTypedArray()
    }

    /** Pid of [pkg] from `pidof` output ("1234" or "1234 5678"); first one wins. */
    fun parsePidof(out: String): Int? = out.trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull()
}
