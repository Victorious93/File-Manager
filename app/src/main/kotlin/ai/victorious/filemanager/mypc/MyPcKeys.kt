package ai.victorious.filemanager.mypc

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * This app's own SSH keys for My PC: one EC P-256 key per PC in Android Keystore, alias
 * `victorpc.<profileId>.filemanager` (VictorSuite spec §4). The private half is generated inside
 * Keystore and is non-exportable: only signatures ever leave it.
 */
object MyPcKeys {
    private const val PROVIDER = "AndroidKeyStore"

    private fun store(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    fun exists(alias: String): Boolean = store().containsAlias(alias)

    /** Returns the public key, generating the key pair the first time. */
    fun ensure(alias: String): ECPublicKey {
        require(alias.startsWith("victorpc.") && alias.endsWith(".filemanager")) { "Not a My PC key alias" }
        val ks = store()
        if (!ks.containsAlias(alias)) {
            val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
            gen.initialize(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build(),
            )
            gen.generateKeyPair()
        }
        return store().getCertificate(alias).publicKey as ECPublicKey
    }

    /** The key pair sshlib signs with. The private key is a Keystore handle, not key bytes. */
    fun keyPair(alias: String): KeyPair {
        val ks = store()
        val priv = ks.getKey(alias, null) as? PrivateKey
            ?: throw IllegalStateException("This PC's key is missing on the phone. Remove the PC and add it again.")
        return KeyPair(ks.getCertificate(alias).publicKey, priv)
    }

    fun delete(alias: String) {
        val ks = store()
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
    }
}
