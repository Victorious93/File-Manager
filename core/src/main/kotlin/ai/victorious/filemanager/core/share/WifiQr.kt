package ai.victorious.filemanager.core.share

/**
 * Wi-Fi join QR payloads in the de-facto `WIFI:` format read by the Android and iOS cameras:
 * `WIFI:T:WPA;S:<ssid>;P:<password>;H:true;;`. Special characters `\ ; , : "` are backslash-escaped.
 */
object WifiQr {
    enum class Security(val code: String) { WPA("WPA"), WEP("WEP"), SAE("SAE"), OPEN("nopass") }

    data class Network(val ssid: String, val password: String?, val security: Security, val hidden: Boolean = false)

    fun escape(s: String): String = buildString { for (c in s) { if (c in "\\;,:\"") append('\\'); append(c) } }

    fun payload(n: Network): String {
        require(n.ssid.isNotEmpty()) { "SSID is empty" }
        if (n.security != Security.OPEN) {
            val p = n.password.orEmpty()
            require(p.isNotEmpty()) { "Password required for ${n.security.code}" }
            if (n.security == Security.WPA || n.security == Security.SAE) require(p.length in 8..63) { "WPA passwords are 8-63 characters" }
        }
        return buildString {
            append("WIFI:T:").append(n.security.code).append(';')
            append("S:").append(escape(n.ssid)).append(';')
            if (n.security != Security.OPEN) append("P:").append(escape(n.password!!)).append(';')
            if (n.hidden) append("H:true;")
            append(';')
        }
    }

    /** Parse a WIFI: payload (useful for tests and for reading scanned codes). */
    fun parse(text: String): Network? {
        if (!text.startsWith("WIFI:")) return null
        val fields = mutableMapOf<String, String>()
        var i = 5
        while (i < text.length) {
            val colon = text.indexOf(':', i).takeIf { it > 0 } ?: break
            val key = text.substring(i, colon)
            val sb = StringBuilder(); var j = colon + 1
            while (j < text.length && text[j] != ';') { if (text[j] == '\\' && j + 1 < text.length) j++; sb.append(text[j]); j++ }
            fields[key] = sb.toString(); i = j + 1
            if (i < text.length && text[i] == ';') break
        }
        val sec = Security.entries.firstOrNull { it.code.equals(fields["T"] ?: "nopass", true) } ?: Security.WPA
        val ssid = fields["S"] ?: return null
        return Network(ssid, fields["P"], sec, fields["H"] == "true")
    }
}
