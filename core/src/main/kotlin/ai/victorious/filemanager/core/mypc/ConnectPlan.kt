package ai.victorious.filemanager.core.mypc

/**
 * Which host to try, in what order, with what TCP connect timeout. LAN addresses get a short
 * timeout (~1.5 s) so an away-from-home phone falls through to the tailnet name quickly.
 *
 * Remote access relies on the official Tailscale app being connected (spec §9): a MagicDNS
 * `*.ts.net` name or a `100.64.0.0/10` address then routes like any other host. VictorSuite's
 * loopback proxy is not a fleet contract, so it is deliberately not used here.
 */
object ConnectPlan {
    const val LAN_TIMEOUT_MS = 1_500
    const val REMOTE_TIMEOUT_MS = 6_000
    const val KEX_TIMEOUT_MS = 15_000

    enum class Kind { LAN, TAILNET, OTHER }

    data class Attempt(val host: String, val kind: Kind, val connectTimeoutMs: Int)

    fun plan(profile: PcProfile): List<Attempt> = profile.connectOrder().map { h ->
        val kind = classify(h)
        Attempt(h, kind, if (kind == Kind.LAN) LAN_TIMEOUT_MS else REMOTE_TIMEOUT_MS)
    }

    fun classify(host: String): Kind {
        val h = host.lowercase().trim('[', ']')
        if (h.endsWith(".ts.net")) return Kind.TAILNET
        if (h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa") || !h.contains('.') && !h.contains(':')) return Kind.LAN
        val v4 = h.split('.').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 4 && h.count { c -> c == '.' } == 3 }
        if (v4 != null) {
            val (a, b) = v4
            return when {
                a == 10 || a == 192 && b == 168 || a == 172 && b in 16..31 || a == 169 && b == 254 -> Kind.LAN
                a == 100 && b in 64..127 -> Kind.TAILNET
                else -> Kind.OTHER
            }
        }
        if (h.contains(':')) {
            return when {
                h.startsWith("fe80") || h.startsWith("fc") || h.startsWith("fd") -> if (h.startsWith("fd7a:115c:a1e0")) Kind.TAILNET else Kind.LAN
                else -> Kind.OTHER
            }
        }
        return Kind.OTHER
    }

    /** sshlib wants IPv6 literals without brackets. */
    fun socketHost(host: String) = host.trim('[', ']')
}
