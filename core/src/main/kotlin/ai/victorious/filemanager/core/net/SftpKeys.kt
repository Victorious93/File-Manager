package ai.victorious.filemanager.core.net

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.util.Base64

/**
 * SFTP private-key support: format detection, validation (including the optional passphrase)
 * and the at-rest secret layout used by [SftpRemoteBackend].
 *
 * Supported keys: OpenSSH (`BEGIN OPENSSH PRIVATE KEY`, RSA/ECDSA/Ed25519, optionally
 * bcrypt-encrypted), traditional PEM (`BEGIN RSA/EC/DSA PRIVATE KEY`, optionally with
 * `Proc-Type: 4,ENCRYPTED`), PKCS#8 (`BEGIN [ENCRYPTED] PRIVATE KEY`) and PuTTY `.ppk`.
 *
 * The key text and passphrase are never logged; callers store [encodeSecret]'s output in the
 * app's Android-Keystore-backed encrypted store.
 */
object SftpKeys {
    enum class Format { OPENSSH, PEM_RSA, PEM_EC, PEM_DSA, PKCS8, PKCS8_ENCRYPTED, PUTTY, UNKNOWN }

    data class KeyInfo(
        val format: Format,
        /** e.g. `ssh-ed25519`, `ssh-rsa`, `ecdsa-sha2-nistp256`. */
        val algorithm: String,
        val encrypted: Boolean,
        /** SHA-256 fingerprint of the public half, OpenSSH style (`SHA256:…`). */
        val fingerprint: String,
    )

    class KeyException(message: String) : IllegalArgumentException(message)

    private const val SECRET_PREFIX = "fm-sshkey:v1\n"

    init { useBouncyCastleEdDsa() }

    fun detectFormat(text: String): Format {
        val t = text.trimStart()
        return when {
            t.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----") -> Format.OPENSSH
            t.startsWith("-----BEGIN RSA PRIVATE KEY-----") -> Format.PEM_RSA
            t.startsWith("-----BEGIN EC PRIVATE KEY-----") -> Format.PEM_EC
            t.startsWith("-----BEGIN DSA PRIVATE KEY-----") -> Format.PEM_DSA
            t.startsWith("-----BEGIN ENCRYPTED PRIVATE KEY-----") -> Format.PKCS8_ENCRYPTED
            t.startsWith("-----BEGIN PRIVATE KEY-----") -> Format.PKCS8
            t.startsWith("PuTTY-User-Key-File-") -> Format.PUTTY
            else -> Format.UNKNOWN
        }
    }

    /**
     * Parses [keyText] and, if it is encrypted, checks [passphrase] unlocks it.
     * @throws KeyException with a user-facing reason (never containing key material).
     */
    fun inspect(keyText: String, passphrase: String? = null): KeyInfo {
        val format = detectFormat(keyText)
        if (format == Format.UNKNOWN) {
            if (keyText.contains("PUBLIC KEY") || keyText.trimStart().startsWith("ssh-")) {
                throw KeyException("That is a public key. Pick the private key file (usually without .pub).")
            }
            throw KeyException("Not a recognised private key (expected OpenSSH, PEM, PKCS#8 or PuTTY).")
        }
        val kp = try {
            KeyPair.load(JSch(), normalize(keyText).toByteArray(Charsets.UTF_8), null)
        } catch (t: Throwable) {
            throw KeyException("Could not read the ${label(format)} key: ${t.message ?: t.javaClass.simpleName}")
        }
        try {
            val encrypted = kp.isEncrypted
            if (encrypted) {
                if (passphrase.isNullOrEmpty()) throw KeyException("This key is protected. Enter its passphrase.")
                if (!kp.decrypt(passphrase.toByteArray(Charsets.UTF_8))) throw KeyException("Wrong passphrase for this key.")
            }
            val algorithm = kp.keyTypeString.ifEmpty { "unknown" }
            val fp = runCatching { kp.fingerPrint }.getOrNull().orEmpty()
            return KeyInfo(format, algorithm, encrypted, fp)
        } finally {
            kp.dispose()
        }
    }

    /** Stored secret for a key login: a versioned header, the base64 passphrase line, then the key text. */
    fun encodeSecret(keyText: String, passphrase: String?): String {
        val pass = passphrase.orEmpty().takeIf { it.isNotEmpty() }
            ?.let { Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8)) }.orEmpty()
        return SECRET_PREFIX + pass + "\n" + normalize(keyText)
    }

    data class Decoded(val keyText: String, val passphrase: String?)

    /** Reverses [encodeSecret]; a bare key (saved before passphrase support) decodes with no passphrase. */
    fun decodeSecret(secret: String): Decoded {
        if (!secret.startsWith(SECRET_PREFIX)) return Decoded(secret, null)
        val rest = secret.substring(SECRET_PREFIX.length)
        val nl = rest.indexOf('\n')
        require(nl >= 0) { "Corrupt key secret" }
        val passB64 = rest.substring(0, nl)
        val pass = passB64.takeIf { it.isNotEmpty() }?.let { String(Base64.getDecoder().decode(it), Charsets.UTF_8) }
        return Decoded(rest.substring(nl + 1), pass)
    }

    /** Adds the key to [jsch] under [name]; the passphrase (if any) unlocks it. */
    fun addIdentity(jsch: JSch, name: String, secret: String) {
        val d = decodeSecret(secret)
        jsch.addIdentity(
            name,
            normalize(d.keyText).toByteArray(Charsets.UTF_8),
            null,
            d.passphrase?.toByteArray(Charsets.UTF_8),
        )
    }

    /** CRLF from Windows editors and stray surrounding whitespace break some parsers. */
    internal fun normalize(text: String): String = text.replace("\r\n", "\n").trim() + "\n"

    private fun label(f: Format) = when (f) {
        Format.OPENSSH -> "OpenSSH"
        Format.PEM_RSA, Format.PEM_EC, Format.PEM_DSA -> "PEM"
        Format.PKCS8, Format.PKCS8_ENCRYPTED -> "PKCS#8"
        Format.PUTTY -> "PuTTY"
        Format.UNKNOWN -> "unknown"
    }

    /**
     * Android has no JDK EdDSA (`java.security.spec.EdECPoint`), so JSch's default jce Ed25519
     * classes fail there. Route Ed25519/Ed448 through JSch's Bouncy Castle implementations,
     * which also work on the desktop JVM used by unit tests.
     */
    @Volatile private var bcConfigured = false

    fun useBouncyCastleEdDsa() {
        if (bcConfigured) return
        synchronized(this) {
            if (bcConfigured) return
            val bcAvailable = runCatching { Class.forName("org.bouncycastle.math.ec.rfc8032.Ed25519") }.isSuccess
            if (bcAvailable) {
                JSch.setConfig("keypairgen.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
                JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
                JSch.setConfig("ssh-ed448", "com.jcraft.jsch.bc.SignatureEd448")
            }
            bcConfigured = true
        }
    }
}
