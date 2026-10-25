package ai.victorious.filemanager.core.apk.mtnp

import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool

/**
 * DEX merge and split via dexlib2 DexPool (BSD-3 / Apache-compatible smali toolchain).
 * Merge: many DEX → one (duplicate class descriptors: first wins, rest listed as skipped).
 * Split: one DEX → N files by class count or approximate byte budget.
 */
object DexMergeSplit {
    data class MergeResult(val dex: ByteArray, val classCount: Int, val skippedDuplicates: List<String>)
    data class SplitPart(val name: String, val dex: ByteArray, val classCount: Int)

    fun load(bytes: ByteArray): DexBackedDexFile = DexBackedDexFile(Opcodes.getDefault(), bytes)

    fun write(classes: Collection<ClassDef>): ByteArray {
        val pool = DexPool(Opcodes.getDefault())
        for (c in classes) pool.internClass(c)
        val store = MemoryDataStore()
        pool.writeTo(store)
        return store.getData()
    }

    fun merge(dexBytes: List<ByteArray>): MergeResult {
        require(dexBytes.isNotEmpty()) { "no DEX inputs" }
        val seen = LinkedHashMap<String, ClassDef>()
        val skipped = mutableListOf<String>()
        for (b in dexBytes) {
            val dex = load(b)
            for (c in dex.classes) {
                if (seen.containsKey(c.type)) skipped += c.type else seen[c.type] = c
            }
        }
        val out = write(seen.values)
        val repaired = DexRepair.repair(out).first
        return MergeResult(repaired, seen.size, skipped)
    }

    /**
     * Split [bytes] into parts of at most [classesPerDex] classes (default 10_000, under the
     * historical 65k method-ish comfort zone for class counts). Names: classes.dex, classes2.dex, …
     */
    fun splitByClassCount(bytes: ByteArray, classesPerDex: Int = 10_000): List<SplitPart> {
        require(classesPerDex > 0)
        val classes = load(bytes).classes.sortedBy { it.type }
        if (classes.isEmpty()) return listOf(SplitPart("classes.dex", DexRepair.repair(bytes).first, 0))
        val parts = mutableListOf<SplitPart>()
        var i = 0
        var part = 1
        while (i < classes.size) {
            val chunk = classes.subList(i, minOf(i + classesPerDex, classes.size))
            val name = if (part == 1) "classes.dex" else "classes$part.dex"
            parts += SplitPart(name, DexRepair.repair(write(chunk)).first, chunk.size)
            i += classesPerDex
            part++
        }
        return parts
    }

    /** Pack classes into DEX files whose unrepaired size stays under [maxBytes] (best-effort). */
    fun splitBySize(bytes: ByteArray, maxBytes: Int = 8_000_000): List<SplitPart> {
        require(maxBytes > 10_000)
        val classes = load(bytes).classes.sortedBy { it.type }
        if (classes.isEmpty()) return splitByClassCount(bytes, 1)
        val parts = mutableListOf<SplitPart>()
        var batch = mutableListOf<ClassDef>()
        var part = 1
        fun flush() {
            if (batch.isEmpty()) return
            val name = if (part == 1) "classes.dex" else "classes$part.dex"
            parts += SplitPart(name, DexRepair.repair(write(batch)).first, batch.size)
            batch = mutableListOf()
            part++
        }
        for (c in classes) {
            batch += c
            val trial = write(batch)
            if (trial.size > maxBytes && batch.size > 1) {
                batch.removeAt(batch.lastIndex)
                flush()
                batch += c
            }
        }
        flush()
        return parts
    }

    /** Class descriptors present in a DEX, sorted. */
    fun classList(bytes: ByteArray): List<String> = load(bytes).classes.map { it.type }.sorted()
}
