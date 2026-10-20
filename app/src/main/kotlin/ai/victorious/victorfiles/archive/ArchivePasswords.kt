package ai.victorious.victorfiles.archive

import ai.victorious.victorfiles.core.archive.ArchivePasswordStore
import ai.victorious.victorfiles.core.archive.BlobStorage
import ai.victorious.victorfiles.core.archive.SecretCodec
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM with a non-exportable Android Keystore key. Blob layout: 12-byte IV || ciphertext+tag.
 * The key never leaves the device, so a blob restored elsewhere cannot be decrypted (the store then
 * loads as empty, by design).
 */
class KeystoreSecretCodec(private val alias: String = ALIAS) : SecretCodec {
    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(cipher: ByteArray): ByteArray {
        require(cipher.size > IV_LEN) { "Blob too short" }
        val c = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, cipher, 0, IV_LEN))
        }
        return c.doFinal(cipher, IV_LEN, cipher.size - IV_LEN)
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val ALIAS = "victorfiles_archive_passwords"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
    }
}

/** Atomic file blob. Lives in noBackupFilesDir so it is never included in cloud backup or device transfer. */
class FileBlobStorage(private val file: File) : BlobStorage {
    override fun read(): ByteArray? = if (file.isFile) file.readBytes() else null

    override fun write(blob: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(blob)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw java.io.IOException("Could not save password store")
        }
    }
}

object ArchivePasswords {
    fun create(context: Context): ArchivePasswordStore = ArchivePasswordStore(
        FileBlobStorage(File(context.noBackupFilesDir, "archive-passwords.bin")),
        KeystoreSecretCodec(),
    )
}
