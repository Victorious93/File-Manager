package ai.victorious.filemanager.core.archive

import java.io.IOException

/** Encrypts/decrypts one secret. The Android app supplies a Keystore-backed implementation. */
interface SecretCodec {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(cipher: ByteArray): ByteArray
}

/** Persists the opaque encrypted blob (e.g. a file in app-private storage). */
interface BlobStorage {
    fun read(): ByteArray?
    fun write(blob: ByteArray)
}

/**
 * Saved "common passwords" tried automatically when extracting encrypted archives.
 *
 * Passwords are never persisted in plaintext: the whole list is serialized and passed through
 * [codec] before reaching [storage]. Order is most-recently-used first, capped at [maxEntries].
 * Not thread-safe; call from one dispatcher.
 */
class ArchivePasswordStore(
    private val storage: BlobStorage,
    private val codec: SecretCodec,
    private val maxEntries: Int = 50,
) {
    init { require(maxEntries > 0) { "maxEntries must be positive" } }

    private var cache: MutableList<String>? = null

    fun list(): List<String> = load().toList()

    /** Adds [password] (or moves it to the front). Blank passwords are rejected. */
    fun add(password: String) {
        require(password.isNotBlank()) { "Password must not be blank" }
        require('\n' !in password && '\r' !in password) { "Password must not contain line breaks" }
        val l = load()
        l.remove(password)
        l.add(0, password)
        while (l.size > maxEntries) l.removeAt(l.lastIndex)
        persist(l)
    }

    fun remove(password: String): Boolean {
        val l = load()
        val removed = l.remove(password)
        if (removed) persist(l)
        return removed
    }

    fun clear() { persist(mutableListOf()) }

    /**
     * Tries each saved password via [attempt], which must throw [WrongPasswordException] or
     * [Archives.RarPasswordException] on a bad password (any other exception propagates).
     * Returns the working password (promoted to front) or null when none worked.
     */
    fun <T> tryAll(attempt: (String) -> T): Pair<String, T>? {
        for (p in list()) {
            try {
                val result = attempt(p)
                add(p)
                return p to result
            } catch (_: WrongPasswordException) {
            } catch (_: Archives.RarPasswordException) {
            }
        }
        return null
    }

    private fun load(): MutableList<String> {
        cache?.let { return it }
        val blob = storage.read()
        val list = if (blob == null) mutableListOf() else try {
            String(codec.decrypt(blob), Charsets.UTF_8).split('\n').filter { it.isNotEmpty() }.toMutableList()
        } catch (e: Exception) {
            // Undecryptable blob (e.g. Keystore key lost): fail closed and start empty rather than crash.
            mutableListOf()
        }
        cache = list
        return list
    }

    private fun persist(list: MutableList<String>) {
        val blob = try {
            codec.encrypt(list.joinToString("\n").toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            throw IOException("Could not encrypt password store", e)
        }
        storage.write(blob)
        cache = list
    }
}
