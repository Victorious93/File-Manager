package ai.victorious.victorfiles.fleet

import ai.victorious.victorfiles.core.fleet.CallerIdentity
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.security.MessageDigest

/** Resolves a uid to its packages and their signing-cert SHA-256 digests. */
class CallerResolver(private val pm: PackageManager) {
    fun resolve(uid: Int): CallerIdentity? {
        if (uid < 0) return null
        val packages = pm.getPackagesForUid(uid)?.toList().orEmpty()
        if (packages.isEmpty()) return null
        return CallerIdentity(uid, packages, packages.associateWith { certDigests(it) })
    }

    @Suppress("DEPRECATION")
    private fun certDigests(pkg: String): List<String> = runCatching {
        val sigs: Array<Signature> = if (Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            val si = info.signingInfo ?: return emptyList()
            if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        } else {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
        } ?: emptyArray()
        sigs.map { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }.getOrDefault(emptyList())
}
