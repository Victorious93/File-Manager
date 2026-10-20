package ai.victorious.filemanager.core.appmgr

import ai.victorious.filemanager.core.shell.ShellCommands

/** The four Android component kinds a blocker can toggle. */
enum class ComponentType(val manifestTag: String) {
    ACTIVITY("activity"), SERVICE("service"), RECEIVER("receiver"), PROVIDER("provider")
}

data class AppComponent(
    val packageName: String,
    val className: String,
    val type: ComponentType,
    val blocked: Boolean = false,
)

/**
 * Component blocker command builders. Written from scratch against public Android/IFW behaviour
 * (no AppManager source consulted). Two backends:
 *  - ROOT: Intent Firewall rules written to /data/system/ifw/<pkg>.xml (survives reboot, no app change).
 *  - SHIZUKU/ROOT pm: `pm disable/enable <pkg>/<class>` (per-component, needs no IFW).
 */
object ComponentBlocker {
    /** `pm disable` a single component (Shizuku/root). Use the `--user` form for multi-user safety. */
    fun pmDisable(pkg: String, className: String, user: Int = 0): String =
        "pm disable --user $user ${ShellCommands.quote("$pkg/$className")}"

    fun pmEnable(pkg: String, className: String, user: Int = 0): String =
        "pm enable --user $user ${ShellCommands.quote("$pkg/$className")}"

    fun ifwPath(pkg: String): String = "/data/system/ifw/$pkg.xml"

    /**
     * Intent Firewall XML for a set of blocked components. The IFW matches by component-name
     * filter; one <activity>/<service>/<broadcast> block per rule with a component-filter.
     * Providers are not IFW-filterable, so the controller falls back to `pm disable` for them.
     */
    fun ifwRules(pkg: String, blocked: List<AppComponent>): String {
        val acts = blocked.filter { it.type == ComponentType.ACTIVITY }
        val svcs = blocked.filter { it.type == ComponentType.SERVICE }
        val recv = blocked.filter { it.type == ComponentType.RECEIVER }
        val sb = StringBuilder()
        sb.append("<rules>\n")
        fun block(tag: String, comps: List<AppComponent>) {
            if (comps.isEmpty()) return
            sb.append("  <$tag block=\"true\" log=\"false\">\n")
            for (c in comps) {
                sb.append("    <component-filter name=\"")
                sb.append(xmlEscape("$pkg/${c.className}"))
                sb.append("\" />\n")
            }
            sb.append("  </$tag>\n")
        }
        block("activity", acts)
        block("service", svcs)
        block("broadcast", recv)
        sb.append("</rules>\n")
        return sb.toString()
    }

    /** Writes the IFW file then fixes its mode (IFW requires 644 and triggers a reload on change). */
    fun writeIfwCommands(pkg: String, xml: String): List<String> {
        val path = ifwPath(pkg)
        return listOf(
            "mkdir -p /data/system/ifw",
            ShellCommands.writeViaStdin(path),
            "chmod 644 ${ShellCommands.quote(path)}",
        )
    }

    fun removeIfwCommand(pkg: String): String = "rm -f ${ShellCommands.quote(ifwPath(pkg))}"

    fun providersNeedPmDisable(blocked: List<AppComponent>): List<AppComponent> =
        blocked.filter { it.type == ComponentType.PROVIDER }

    private fun xmlEscape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}

/** Whole-app freeze/enable state. "Freeze" = disabled-until-used/user-disabled; data is kept. */
object AppState {
    fun disable(pkg: String, user: Int = 0): String = "pm disable-user --user $user ${ShellCommands.quote(pkg)}"
    fun enable(pkg: String, user: Int = 0): String = "pm enable --user $user ${ShellCommands.quote(pkg)}"
    /** "Hide" is the closest to a true freeze on newer Android; falls back to disable-user. */
    fun suspend(pkg: String, user: Int = 0): String = "pm suspend --user $user ${ShellCommands.quote(pkg)}"
    fun unsuspend(pkg: String, user: Int = 0): String = "pm unsuspend --user $user ${ShellCommands.quote(pkg)}"
    fun forceStop(pkg: String): String = "am force-stop ${ShellCommands.quote(pkg)}"
    fun clearData(pkg: String, user: Int = 0): String = "pm clear --user $user ${ShellCommands.quote(pkg)}"
}
