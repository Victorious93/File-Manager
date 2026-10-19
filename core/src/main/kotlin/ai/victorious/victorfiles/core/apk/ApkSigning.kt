package ai.victorious.victorfiles.core.apk

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Which APK signature schemes to apply. */
data class SignSchemes(val v1: Boolean = true, val v2: Boolean = true, val v3: Boolean = true, val v4: Boolean = false) {
    init { require(v1 || v2 || v3 || v4) { "Enable at least one signature scheme" } }
}

/** A key + certificate chain loaded from a user keystore or the app's debug keystore. */
class SigningKey(val privateKey: PrivateKey, val certs: List<X509Certificate>) {
    val subject: String get() = certs.first().subjectX500Principal.name
}

data class SignerInfo(val subject: String, val sha256: String)

data class VerifyResult(
    val verified: Boolean,
    val v1: Boolean,
    val v2: Boolean,
    val v3: Boolean,
    val v4: Boolean,
    val signers: List<SignerInfo>,
    val errors: List<String>,
)

/**
 * APK signing via Google's apksig (Apache-2.0): v1 (JAR), v2, v3 and optional v4 (.apk.idsig) schemes.
 * Keys are never bundled: either a user-provided keystore (PKCS12 / JKS where the platform
 * supports it) or a per-install debug keystore generated on-device by [generateDebugKeystore].
 */
object ApkSigning {
    const val DEBUG_ALIAS = "victorious-debug"
    const val DEFAULT_MIN_SDK = 21

    /** V1 signer names must be uppercase ASCII letters/digits/'-'/'_' (max 8 chars is conventional). */
    fun v1SignerName(alias: String): String =
        alias.uppercase(Locale.ROOT).filter { it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }
            .take(8).ifEmpty { "CERT" }

    fun signedNameFor(apk: File): File {
        val base = apk.nameWithoutExtension.removeSuffix("_unsigned") + "_signed"
        var f = File(apk.absoluteFile.parentFile, "$base.apk")
        var i = 2
        while (f.exists()) f = File(apk.absoluteFile.parentFile, "${base}_${i++}.apk")
        return f
    }

    fun keystoreType(file: File): String = when (file.extension.lowercase(Locale.ROOT)) {
        "jks", "keystore" -> runCatching { KeyStore.getInstance("JKS"); "JKS" }.getOrDefault("PKCS12")
        "bks" -> "BKS"
        else -> "PKCS12"
    }

    fun loadKey(keystore: File, storePassword: CharArray, alias: String?, keyPassword: CharArray? = null): SigningKey {
        val ks = loadKeystore(keystore, storePassword)
        val a = alias?.takeIf { it.isNotBlank() } ?: ks.aliases().toList().firstOrNull { ks.isKeyEntry(it) }
            ?: throw IllegalArgumentException("No private key entry in ${keystore.name}")
        val key = ks.getKey(a, keyPassword ?: storePassword) as? PrivateKey
            ?: throw IllegalArgumentException("Alias '$a' has no private key")
        val chain = ks.getCertificateChain(a)?.map { it as X509Certificate }
            ?: throw IllegalArgumentException("Alias '$a' has no certificate chain")
        return SigningKey(key, chain)
    }

    private fun loadKeystore(file: File, pass: CharArray): KeyStore {
        val types = listOf(keystoreType(file), "PKCS12", "JKS", "BKS").distinct()
        var last: Exception? = null
        for (t in types) {
            try {
                return KeyStore.getInstance(t).apply { file.inputStream().use { load(it, pass) } }
            } catch (e: Exception) { last = e }
        }
        throw IllegalArgumentException("Cannot open keystore ${file.name}: ${last?.message}", last)
    }

    /**
     * Signs [input] into [output]. minSdk is read from the manifest by apksig; if that fails
     * (e.g. hand-edited folder) [fallbackMinSdk] is used.
     */
    fun sign(input: File, output: File, key: SigningKey, alias: String = "CERT", schemes: SignSchemes = SignSchemes(), fallbackMinSdk: Int? = null) {
        require(input.canonicalPath != output.canonicalPath) { "Output must differ from input" }
        val config = ApkSigner.SignerConfig.Builder(v1SignerName(alias), key.privateKey, key.certs).build()
        val builder = ApkSigner.Builder(listOf(config))
            .setInputApk(input)
            .setOutputApk(output)
            .setV1SigningEnabled(schemes.v1)
            .setV2SigningEnabled(schemes.v2)
            .setV3SigningEnabled(schemes.v3)
            .setV4SigningEnabled(schemes.v4)
            .setCreatedBy("VictorFiles")
        if (schemes.v4) builder.setV4SignatureOutputFile(File(output.path + ".idsig"))
        val minSdk = fallbackMinSdk ?: runCatching { AxmlParser.fromApk(input).minSdk }.getOrNull()
        if (minSdk == null) builder.setMinSdkVersion(DEFAULT_MIN_SDK) else builder.setMinSdkVersion(minSdk)
        builder.build().sign()
    }

    fun verify(apk: File, minSdk: Int? = null): VerifyResult {
        val b = ApkVerifier.Builder(apk)
        if (minSdk != null) b.setMinCheckedPlatformVersion(minSdk)
        val r = b.build().verify()
        val signers = (r.signerCertificates.orEmpty()).map { c ->
            SignerInfo(
                c.subjectX500Principal.name,
                java.security.MessageDigest.getInstance("SHA-256").digest(c.encoded)
                    .joinToString("") { b -> "%02x".format(b) },
            )
        }
        val v4 = runCatching {
            // ApkVerifier may expose V4 via isVerifiedUsingV4Scheme on newer apksig; fall back to false.
            r.javaClass.methods.any { it.name == "isVerifiedUsingV4Scheme" && (it.invoke(r) as Boolean) }
        }.getOrDefault(false)
        return VerifyResult(
            r.isVerified, r.isVerifiedUsingV1Scheme, r.isVerifiedUsingV2Scheme, r.isVerifiedUsingV3Scheme,
            v4, signers, r.errors.map { it.toString() },
        )
    }

    /**
     * Creates a PKCS12 keystore with a fresh RSA-2048 key and a self-signed certificate
     * (CN=[commonName], valid [years] years). Used once per install for the in-app debug key.
     */
    fun generateDebugKeystore(out: File, password: CharArray, alias: String = DEBUG_ALIAS, commonName: String = "Victorious File Manager Debug", years: Int = 30) {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.generateKeyPair()
        val now = System.currentTimeMillis()
        val cert = SelfSignedCert.create(kp.public.encoded, kp.private, commonName, Date(now - 60_000), Date(now + years * 365L * 86_400_000L))
        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        ks.setKeyEntry(alias, kp.private, password, arrayOf(cert))
        out.absoluteFile.parentFile?.mkdirs()
        out.outputStream().use { ks.store(it, password) }
    }
}

/** Tiny DER encoder that builds a v3 self-signed X.509 certificate (SHA256withRSA). No BouncyCastle needed. */
internal object SelfSignedCert {
    private val SHA256_RSA = byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B)
    private val CN_OID = byteArrayOf(0x55, 0x04, 0x03)

    fun create(spki: ByteArray, key: PrivateKey, cn: String, from: Date, to: Date): X509Certificate {
        val algId = seq(tlv(0x06, SHA256_RSA), byteArrayOf(0x05, 0x00))
        val name = seq(tlv(0x31, seq(tlv(0x06, CN_OID), tlv(0x0C, cn.toByteArray(Charsets.UTF_8)))))
        val serial = BigInteger(64, SecureRandom()).add(BigInteger.ONE)
        val tbs = seq(
            tlv(0xA0, tlv(0x02, byteArrayOf(2))),
            tlv(0x02, serial.toByteArray()),
            algId, name,
            seq(time(from), time(to)),
            name, spki,
        )
        val sig = Signature.getInstance("SHA256withRSA").apply { initSign(key); update(tbs) }.sign()
        val der = seq(tbs, algId, tlv(0x03, byteArrayOf(0) + sig))
        return CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
    }

    private fun time(d: Date): ByteArray {
        val utc = TimeZone.getTimeZone("UTC")
        val year = java.util.Calendar.getInstance(utc).apply { time = d }.get(java.util.Calendar.YEAR)
        return if (year < 2050) tlv(0x17, fmt("yyMMddHHmmss'Z'", d, utc)) else tlv(0x18, fmt("yyyyMMddHHmmss'Z'", d, utc))
    }

    private fun fmt(p: String, d: Date, tz: TimeZone) =
        SimpleDateFormat(p, Locale.US).apply { timeZone = tz }.format(d).toByteArray(Charsets.US_ASCII)

    private fun seq(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })

    fun tlv(tag: Int, value: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val n = value.size
        when {
            n < 0x80 -> out.write(n)
            n < 0x100 -> { out.write(0x81); out.write(n) }
            n < 0x10000 -> { out.write(0x82); out.write(n shr 8); out.write(n and 0xFF) }
            else -> { out.write(0x83); out.write(n shr 16); out.write((n shr 8) and 0xFF); out.write(n and 0xFF) }
        }
        out.write(value)
        return out.toByteArray()
    }
}
