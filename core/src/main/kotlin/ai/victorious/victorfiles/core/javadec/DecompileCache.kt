package ai.victorious.victorfiles.core.javadec

import java.io.File
import java.security.MessageDigest

/**
 * Pure cache logic for decompiled Java, keyed by (dex content hash + class name) so a given
 * class in a given dex always maps to one stable, collision-resistant cache file. The on-disk
 * store is kept under a byte budget with simple LRU (oldest-modified) eviction.
 */
object DecompileCache {
    /** Stable cache key for a class inside a specific dex (identified by its content hash). */
    fun key(dexHash: String, className: String): String =
        sha256Hex("$dexHash|$className")

    fun file(dir: File, key: String): File = File(dir, "$key.java")

    fun read(dir: File, key: String): String? =
        file(dir, key).takeIf { it.isFile }?.readText()

    /** Writes [code] for [key] then enforces [budgetBytes] by evicting oldest files. */
    fun write(dir: File, key: String, code: String, budgetBytes: Long) {
        dir.mkdirs()
        file(dir, key).writeText(code)
        enforceBudget(dir, budgetBytes)
    }

    fun enforceBudget(dir: File, budgetBytes: Long) {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".java") }?.toMutableList() ?: return
        var total = files.sumOf { it.length() }
        if (total <= budgetBytes) return
        files.sortBy { it.lastModified() } // oldest first
        for (f in files) {
            if (total <= budgetBytes) break
            val len = f.length()
            if (f.delete()) total -= len
        }
    }

    private fun sha256Hex(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
