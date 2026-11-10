package ai.victorious.filemanager.core.mypc

import java.security.SecureRandom
import kotlinx.serialization.Serializable

/**
 * One paired (or manually added) PC. Public data only, like the provider row in VictorSuite's
 * spec §5: no token, password, private key or proxy address is ever stored here. The private key
 * lives in Android Keystore under [keyAlias] and cannot be exported.
 */
@Serializable
data class PcProfile(
    /** 12 base32 chars (spec §1). */
    val profileId: String,
    val name: String,
    /** Login user, POSIX name. */
    val user: String,
    /** 1–8 hosts in preferred connect order (LAN first, then tailnet). Hints only; identity is [hostKeys]. */
    val hosts: List<String>,
    val port: Int = 22,
    /** Host-key pins (spec §1 form). Empty only for a manual profile that has not been confirmed yet. */
    val hostKeys: List<String> = emptyList(),
    /** Optional remote start folder; blank = the user's home. */
    val startPath: String = "",
    val pairedAt: Long = 0L,
    val lastSeenHost: String? = null,
    val lastSeenAt: Long? = null,
    /** How the profile was created. P1 only creates [Source.MANUAL]. */
    val source: Source = Source.MANUAL,
) {
    @Serializable
    enum class Source { MANUAL, VICTORSUITE }

    val keyAlias: String get() = MyPcIds.keyAlias(profileId)
    val connectionId: String get() = MyPcIds.connectionId(profileId)

    /** Hosts with the one that last answered with a pinned key tried first. */
    fun connectOrder(): List<String> {
        val last = lastSeenHost ?: return hosts
        return if (last in hosts) listOf(last) + hosts.filterNot { it == last } else hosts
    }

    override fun toString(): String =
        "PcProfile(id=$profileId, name=$name, user=$user, hosts=$hosts, port=$port, pins=${hostKeys.size})"
}

object MyPcIds {
    /** The app name in victorpc key aliases and authorized_keys comments (spec §1, §8). */
    const val APP = "filemanager"
    const val CONNECTION_PREFIX = "mypc-"
    private const val B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    // Spec §1 says RFC 4648 base32 (A–Z, 2–7), but its own examples (7Q2M4KX9TB5N,
    // K3VQ7M2XH9PDT4WA) contain '9'. Generate strictly, accept any upper-case alphanumeric so ids
    // VictorSuite hands over later are never refused over that ambiguity.
    private val PROFILE_ID = Regex("[A-Z0-9]{12}")
    private val DEVICE_ID = Regex("[A-Z0-9]{16}")

    fun isProfileId(s: String) = PROFILE_ID.matches(s)
    fun isDeviceId(s: String) = DEVICE_ID.matches(s)

    fun newProfileId(random: SecureRandom = SecureRandom()) = randomBase32(12, random)
    fun newDeviceId(random: SecureRandom = SecureRandom()) = randomBase32(16, random)

    private fun randomBase32(n: Int, random: SecureRandom) = buildString { repeat(n) { append(B32[random.nextInt(32)]) } }

    /** `victorpc.<profileId>.filemanager`: the Keystore alias of this app's own key for one PC. */
    fun keyAlias(profileId: String): String {
        require(isProfileId(profileId)) { "Bad profile id" }
        return "victorpc.$profileId.$APP"
    }

    /** `victorpc:<deviceId>:filemanager`: the authorized_keys comment for this app's key. */
    fun keyComment(deviceId: String): String {
        require(isDeviceId(deviceId)) { "Bad device id" }
        return "victorpc:$deviceId:$APP"
    }

    /** Virtual-path connection id: My PC panes live under `/remote/mypc-<profileId>/…`. */
    fun connectionId(profileId: String) = CONNECTION_PREFIX + profileId

    fun profileIdOf(connectionId: String): String? =
        connectionId.removePrefix(CONNECTION_PREFIX).takeIf { connectionId.startsWith(CONNECTION_PREFIX) && isProfileId(it) }
}

/** Validation for the manual "Add PC" form, using the same rules as the QR payload (spec §2). */
object PcProfileRules {
    private val USER = Regex("[a-z_][a-z0-9_-]{0,31}")
    private val DNS = Regex("(?=.{1,253}$)([A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)(\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*\\.?")
    private val IPV4 = Regex("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})")

    fun validUser(u: String) = USER.matches(u)

    fun validHost(h: String): Boolean {
        if (h.startsWith("[") && h.endsWith("]")) {
            val inner = h.substring(1, h.length - 1)
            return inner.contains(':') && inner.all { it.isLetterOrDigit() || it == ':' || it == '.' || it == '%' }
        }
        IPV4.matchEntire(h)?.let { m -> return m.groupValues.drop(1).all { it.toInt() in 0..255 } }
        if (h.all { it.isDigit() || it == '.' }) return false
        return DNS.matches(h)
    }

    /** Splits the hosts field (commas or spaces), keeping order and dropping duplicates. */
    fun parseHosts(text: String): List<String> =
        text.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** Returns a list of plain-English problems; empty means the form is fine. */
    fun problems(name: String, user: String, hosts: List<String>, port: Int?, pinsText: String): List<String> = buildList {
        if (name.isBlank() || name.length > 64 || name.any { it.isISOControl() }) add("Name must be 1–64 characters.")
        if (!validUser(user)) add("User must be a Linux user name like don (lowercase letters, digits, _ or -).")
        if (hosts.isEmpty() || hosts.size > 8) add("Enter 1 to 8 hosts, LAN address first.")
        hosts.filterNot(::validHost).forEach { add("Not a host name or IP address: $it") }
        if (port == null || port !in 1..65535) add("Port must be 1–65535 (normally 22).")
        runCatching { HostKeys.parseList(pinsText) }.onFailure { add(it.message ?: "Bad host key pin") }
    }
}
