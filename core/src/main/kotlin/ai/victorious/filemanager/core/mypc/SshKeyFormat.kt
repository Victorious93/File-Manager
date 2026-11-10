package ai.victorious.filemanager.core.mypc

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64

/**
 * OpenSSH wire form for this app's EC P-256 key (RFC 5656 §3.1): string "ecdsa-sha2-nistp256",
 * string "nistp256", string Q (uncompressed point 0x04 || X || Y).
 */
object SshKeyFormat {
    const val ECDSA_P256 = "ecdsa-sha2-nistp256"
    private const val CURVE = "nistp256"

    fun blob(key: ECPublicKey): ByteArray {
        require(key.params.curve.field.fieldSize == 256) { "Only P-256 keys are supported" }
        val q = ByteArrayOutputStream()
        q.write(0x04)
        q.write(fixed32(key.w.affineX))
        q.write(fixed32(key.w.affineY))
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            sshString(d, ECDSA_P256.toByteArray(Charsets.US_ASCII))
            sshString(d, CURVE.toByteArray(Charsets.US_ASCII))
            sshString(d, q.toByteArray())
        }
        return out.toByteArray()
    }

    /** `ecdsa-sha2-nistp256 AAAA…` with no comment: the spec §1 "public key" form. */
    fun publicKey(key: ECPublicKey): String = ECDSA_P256 + " " + Base64.getEncoder().encodeToString(blob(key))

    /** The line to paste into `~/.ssh/authorized_keys`. `restrict` matches what VictorSuite writes (spec §6). */
    fun authorizedKeysLine(key: ECPublicKey, deviceId: String): String =
        "restrict " + publicKey(key) + " " + MyPcIds.keyComment(deviceId)

    /** Parses a P-256 blob back to a key (tests, and checking pasted keys). */
    fun parseP256(blob: ByteArray): ECPublicKey {
        val r = Reader(blob)
        require(r.string().decodeToString() == ECDSA_P256) { "Not an ecdsa-sha2-nistp256 key" }
        require(r.string().decodeToString() == CURVE) { "Not a nistp256 key" }
        val q = r.string()
        require(q.size == 65 && q[0] == 0x04.toByte()) { "Bad EC point" }
        val x = BigInteger(1, q.copyOfRange(1, 33))
        val y = BigInteger(1, q.copyOfRange(33, 65))
        val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params)) as ECPublicKey
    }

    private fun fixed32(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size == 33 && raw[0] == 0.toByte() -> raw.copyOfRange(1, 33)
            raw.size < 32 -> ByteArray(32 - raw.size) + raw
            else -> error("Coordinate too large")
        }
    }

    private fun sshString(d: DataOutputStream, b: ByteArray) {
        d.writeInt(b.size)
        d.write(b)
    }

    private class Reader(private val b: ByteArray) {
        private var pos = 0
        fun string(): ByteArray {
            require(pos + 4 <= b.size) { "Truncated key" }
            val len = ((b[pos].toInt() and 0xff) shl 24) or ((b[pos + 1].toInt() and 0xff) shl 16) or
                ((b[pos + 2].toInt() and 0xff) shl 8) or (b[pos + 3].toInt() and 0xff)
            pos += 4
            require(len in 0..4096 && pos + len <= b.size) { "Truncated key" }
            return b.copyOfRange(pos, pos + len).also { pos += len }
        }
    }
}
