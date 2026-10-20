package ai.victorious.filemanager.core.apk.mtnp

import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.rewriter.DexRewriter
import com.android.tools.smali.dexlib2.rewriter.Rewriter
import com.android.tools.smali.dexlib2.rewriter.RewriterModule
import com.android.tools.smali.dexlib2.rewriter.Rewriters
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool

/**
 * List / replace string literals in a DEX via dexlib2 DexRewriter + DexPool.
 * Adding a brand-new unused string is done by replacing a placeholder or by merging a tiny
 * helper class; pool-only append without a reference is stripped on rewrite.
 */
object DexStringEdit {
    data class StringHit(val value: String, val index: Int)

    fun listStrings(bytes: ByteArray): List<StringHit> {
        val dex = DexBackedDexFile(Opcodes.getDefault(), bytes)
        return dex.stringSection.mapIndexed { i, s -> StringHit(s, i) }
    }

    /** Replace every occurrence of keys in [edits] (old → new). Returns repaired DEX bytes. */
    fun replaceStrings(bytes: ByteArray, edits: Map<String, String>): ByteArray {
        if (edits.isEmpty()) return bytes
        val dex = DexBackedDexFile(Opcodes.getDefault(), bytes)
        val rewriter = DexRewriter(object : RewriterModule() {
            override fun getStringRewriter(rewriters: Rewriters): Rewriter<String> =
                Rewriter { value -> edits[value] ?: value }
        })
        val rewritten = rewriter.dexFileRewriter.rewrite(dex)
        val pool = DexPool(Opcodes.getDefault())
        for (c in rewritten.classes) pool.internClass(c)
        val store = MemoryDataStore()
        pool.writeTo(store)
        return DexRepair.repair(store.getData()).first
    }

    /** Convenience: apply a single old→new string edit. */
    fun replaceOne(bytes: ByteArray, old: String, new: String): ByteArray =
        replaceStrings(bytes, mapOf(old to new))
}
