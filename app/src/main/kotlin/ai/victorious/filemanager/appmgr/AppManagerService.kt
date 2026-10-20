package ai.victorious.filemanager.appmgr

import ai.victorious.filemanager.core.appmgr.AppComponent
import ai.victorious.filemanager.core.appmgr.AppOpsManagerOps
import ai.victorious.filemanager.core.appmgr.AppProfile
import ai.victorious.filemanager.core.appmgr.AppState
import ai.victorious.filemanager.core.appmgr.BatchOps
import ai.victorious.filemanager.core.appmgr.BatchPlan
import ai.victorious.filemanager.core.appmgr.ComponentBlocker
import ai.victorious.filemanager.core.appmgr.ComponentType
import ai.victorious.filemanager.core.appmgr.LogFilter
import ai.victorious.filemanager.core.appmgr.LogLine
import ai.victorious.filemanager.core.appmgr.LogcatFilter
import ai.victorious.filemanager.core.appmgr.LogcatParser
import ai.victorious.filemanager.core.appmgr.PermissionOps
import ai.victorious.filemanager.core.appmgr.ProfileCodec
import ai.victorious.filemanager.core.appmgr.ProfileRunner
import ai.victorious.filemanager.core.appmgr.RunningApps
import ai.victorious.filemanager.core.appmgr.RunningProcess
import ai.victorious.filemanager.core.appmgr.TrackerHit
import ai.victorious.filemanager.core.appmgr.TrackerScanner
import ai.victorious.filemanager.core.priv.PrivilegeBackend
import ai.victorious.filemanager.core.shell.ShellCommands
import ai.victorious.filemanager.priv.PrivilegeManager
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

/**
 * App Manager Pro — Android side. Written from scratch (AppManager's public feature list was
 * used as a guide only; none of its source was read). Destructive actions are planned here and
 * executed only after the UI confirms.
 */
class AppManagerService(private val context: Context, private val privilege: PrivilegeManager) {
    private val pm: PackageManager = context.packageManager
    private val profilesFile = File(context.filesDir, "app_manager_profiles.json")

    data class InstalledApp(
        val packageName: String,
        val label: String,
        val isSystem: Boolean,
        val enabled: Boolean,
        val versionName: String?,
        val versionCode: Long,
        val uid: Int,
    )

    fun listApps(includeSystem: Boolean = true): List<InstalledApp> {
        val flags = PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_UNINSTALLED_PACKAGES
        return pm.getInstalledApplications(flags).mapNotNull { ai ->
            val sys = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (!includeSystem && sys) return@mapNotNull null
            val pi = runCatching { pm.getPackageInfo(ai.packageName, 0) }.getOrNull()
            InstalledApp(
                ai.packageName,
                runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(ai.packageName),
                sys,
                ai.enabled,
                pi?.versionName,
                if (Build.VERSION.SDK_INT >= 28) pi?.longVersionCode ?: 0 else @Suppress("DEPRECATION") pi?.versionCode?.toLong() ?: 0,
                ai.uid,
            )
        }.sortedBy { it.label.lowercase() }
    }

    fun components(pkg: String): List<AppComponent> {
        val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS or PackageManager.MATCH_DISABLED_COMPONENTS
        val pi = pm.getPackageInfo(pkg, flags)
        fun map(type: ComponentType, infos: Array<out android.content.pm.ComponentInfo>?) =
            infos.orEmpty().map {
                AppComponent(pkg, it.name, type, !it.enabled)
            }
        return map(ComponentType.ACTIVITY, pi.activities) +
            map(ComponentType.SERVICE, pi.services) +
            map(ComponentType.RECEIVER, pi.receivers) +
            map(ComponentType.PROVIDER, pi.providers)
    }

    /** Class names from the package's own components + declared libraries — enough for tracker prefixes. */
    fun classHints(pkg: String): List<String> = components(pkg).map { it.className }

    fun scanTrackers(pkg: String): List<TrackerHit> = TrackerScanner.scan(classHints(pkg))

    fun permissions(pkg: String): List<Pair<String, Boolean>> {
        val requested = runCatching {
            pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS).requestedPermissions?.toList().orEmpty()
        }.getOrDefault(emptyList())
        val granted = if (needsPrivilege()) {
            val dump = run("dumpsys package ${ShellCommands.quote(pkg)}")
            PermissionOps.parseGranted(dump)
        } else emptyMap()
        return requested.map { it to (granted[it] ?: (pm.checkPermission(it, pkg) == PackageManager.PERMISSION_GRANTED)) }
    }

    fun appOps(pkg: String): Map<String, AppOpsManagerOps.Mode> {
        if (!needsPrivilege()) return emptyMap()
        return AppOpsManagerOps.parse(run(AppOpsManagerOps.get(pkg)))
    }

    // ---- plans (UI confirms, then [execute]) ----

    fun planBlock(comps: List<AppComponent>): BatchPlan {
        requireElevated("block components")
        return if (privilege.backend() == PrivilegeBackend.ROOT) {
            // IFW for A/S/R; pm disable for providers.
            val byPkg = comps.groupBy { it.packageName }
            val cmds = mutableListOf<String>()
            val items = mutableListOf<String>()
            for ((pkg, list) in byPkg) {
                val ifw = list.filter { it.type != ComponentType.PROVIDER }
                if (ifw.isNotEmpty()) {
                    // Marker command: execute() special-cases "IFW_WRITE:" to use PrivilegedOps.writeText.
                    cmds += "IFW_WRITE:${ComponentBlocker.ifwPath(pkg)}"
                    cmds += ComponentBlocker.ifwRules(pkg, ifw)
                    cmds += "chmod 644 ${ShellCommands.quote(ComponentBlocker.ifwPath(pkg))}"
                }
                for (p in list.filter { it.type == ComponentType.PROVIDER }) {
                    cmds += ComponentBlocker.pmDisable(p.packageName, p.className)
                }
                items += list.map { "${it.packageName}/${it.className}" }
            }
            BatchPlan("Block components (IFW + pm)", items, cmds)
        } else {
            BatchOps.blockComponents(comps)
        }
    }

    fun planUnblock(comps: List<AppComponent>): BatchPlan {
        requireElevated("unblock components")
        return if (privilege.backend() == PrivilegeBackend.ROOT) {
            val cmds = comps.map { ComponentBlocker.pmEnable(it.packageName, it.className) } +
                comps.map { it.packageName }.distinct().map(ComponentBlocker::removeIfwCommand)
            BatchPlan("Unblock components", comps.map { "${it.packageName}/${it.className}" }, cmds)
        } else BatchOps.unblockComponents(comps)
    }

    fun planDisable(pkgs: List<String>) = BatchOps.disable(pkgs).also { requireElevated("disable apps") }
    fun planEnable(pkgs: List<String>) = BatchOps.enable(pkgs).also { requireElevated("enable apps") }
    fun planForceStop(pkgs: List<String>) = BatchOps.forceStop(pkgs).also { requireElevated("force-stop") }
    fun planSuspend(pkgs: List<String>) = BatchPlan("Suspend apps", pkgs, pkgs.map(AppState::suspend)).also { requireElevated("suspend") }
    fun planUnsuspend(pkgs: List<String>) = BatchPlan("Unsuspend apps", pkgs, pkgs.map(AppState::unsuspend)).also { requireElevated("unsuspend") }
    fun planClearData(pkg: String) = BatchPlan("Clear data", listOf(pkg), listOf(AppState.clearData(pkg))).also { requireElevated("clear data") }
    fun planGrant(pkg: String, perm: String) = BatchPlan("Grant permission", listOf("$pkg ← $perm"), listOf(PermissionOps.grant(pkg, perm))).also { requireElevated("grant") }
    fun planRevoke(pkg: String, perm: String) = BatchPlan("Revoke permission", listOf("$pkg → $perm"), listOf(PermissionOps.revoke(pkg, perm))).also { requireElevated("revoke") }
    fun planAppOp(pkg: String, op: String, mode: AppOpsManagerOps.Mode) =
        BatchPlan("Set AppOp", listOf("$pkg $op=${mode.cli}"), listOf(AppOpsManagerOps.set(pkg, op, mode))).also { requireElevated("appops") }
    fun planBlockTrackers(pkg: String): BatchPlan {
        val comps = TrackerScanner.trackerComponents(components(pkg))
        if (comps.isEmpty()) return BatchPlan("No tracker components", emptyList(), emptyList())
        return planBlock(comps)
    }
    fun planProfile(profile: AppProfile) = ProfileRunner.plan(profile).let { BatchPlan(profile.name, it.summary, it.commands) }
        .also { requireElevated("apply profile") }

    /** Runs a confirmed plan. Returns combined stdout/stderr of the last failing command, or null. */
    fun execute(plan: BatchPlan): String? {
        if (plan.commands.isEmpty()) return null
        var lastErr: String? = null
        var i = 0
        while (i < plan.commands.size) {
            val cmd = plan.commands[i]
            if (cmd.startsWith("IFW_WRITE:") && i + 1 < plan.commands.size) {
                val path = cmd.removePrefix("IFW_WRITE:")
                val xml = plan.commands[i + 1]
                runCatching {
                    privilege.execElevated("mkdir -p /data/system/ifw")
                    privilege.ops().writeText(path, xml)
                }.onFailure { lastErr = it.message }
                i += 2
                continue
            }
            val r = privilege.execElevated(cmd)
            if (r.code != 0) lastErr = r.stderr.ifBlank { r.stdout }.ifBlank { "exit ${r.code}" }
            i++
        }
        return lastErr
    }

    // ---- logcat / running / usage ----

    fun dumpLogcat(maxLines: Int = 2000): List<LogLine> {
        val cmd = "logcat -d -v threadtime -t $maxLines"
        val text = if (needsPrivilege()) run(cmd) else {
            // Unprivileged: still works for the caller's own logs on many devices.
            runCatching {
                val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                p.inputStream.bufferedReader().readText().also { p.waitFor() }
            }.getOrDefault("")
        }
        return LogcatParser.parseAll(text)
    }

    fun filterLog(lines: List<LogLine>, filter: LogFilter) = LogcatFilter.apply(lines, filter)
    fun exportLog(lines: List<LogLine>) = LogcatFilter.export(lines)

    fun runningProcesses(): List<RunningProcess> {
        val text = if (needsPrivilege()) run("ps -A -o USER,PID,NAME") else
            runCatching {
                val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "ps -A -o USER,PID,NAME"))
                p.inputStream.bufferedReader().readText().also { p.waitFor() }
            }.getOrDefault("")
        return RunningApps.parsePs(text)
    }

    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun usage(startMs: Long, endMs: Long): List<ai.victorious.filemanager.core.appmgr.AppUsage> {
        if (!hasUsageAccess()) return emptyList()
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startMs, endMs).orEmpty()
        return ai.victorious.filemanager.core.appmgr.UsageAggregator.merge(
            stats.map {
                ai.victorious.filemanager.core.appmgr.AppUsage(
                    it.packageName, it.totalTimeInForeground, it.lastTimeUsed,
                )
            },
        )
    }

    // ---- profiles ----
    fun loadProfiles(): List<AppProfile> = ProfileCodec.decode(runCatching { profilesFile.readText() }.getOrDefault(""))
    fun saveProfiles(list: List<AppProfile>) { profilesFile.writeText(ProfileCodec.encode(list)) }

    fun needsPrivilege(): Boolean = privilege.backend() != PrivilegeBackend.NORMAL
    private fun requireElevated(what: String) {
        if (!needsPrivilege()) error("Root or Shizuku is required to $what")
    }
    private fun run(cmd: String): String {
        val r = privilege.execElevated(cmd)
        return (r.stdout + "\n" + r.stderr).trim()
    }
}
