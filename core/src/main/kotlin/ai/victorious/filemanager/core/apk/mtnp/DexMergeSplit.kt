package ai.victorious.filemanager.core.apk.mtnp

import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool

/**
 * DEX merge and split via dexlib2 DexPool (BSD-3 / Apache-compatible smali toolchain).
 * Merge: many DEX → one (or rebalance under the 64k method limit); duplicate class
 * descriptors resolved by [DuplicatePolicy].
 * Split: one DEX → N files by class count, approximate byte budget, or method count.
 */
object DexMergeSplit {
    /** Historical Dalvik/ART per-DEX method reference limit. Leave a small cushion. */
    const val METHOD_LIMIT = 65_536
    const val DEFAULT_METHOD_BUDGET = 65_000

    enum class DuplicatePolicy {
        /** Keep the class from the earliest input DEX. */
        FIRST,
        /** Keep the class from the latest input DEX. */
        LAST,
        /** Keep the class that declares more methods (ties → first). */
        PREFER_LARGER,
    }

    data class MergeResult(
        val dex: ByteArray,
        val classCount: Int,
        val methodCount: Int,
        val skippedDuplicates: List<String>,
        val winners: Map<String, String> = emptyMap(),
    )

    data class SplitPart(val name: String, val dex: ByteArray, val classCount: Int, val methodCount: Int = 0)

    fun load(bytes: ByteArray): DexBackedDexFile = DexBackedDexFile(Opcodes.getDefault(), bytes)

    fun write(classes: Collection<ClassDef>): ByteArray {
        val pool = DexPool(Opcodes.getDefault())
        for (c in classes) pool.internClass(c)
        val store = MemoryDataStore()
        pool.writeTo(store)
        return store.getData()
    }

    fun methodCount(cls: ClassDef): Int = cls.methods.count()

    fun methodCount(classes: Collection<ClassDef>): Int = classes.sumOf { methodCount(it) }

    fun merge(
        dexBytes: List<ByteArray>,
        policy: DuplicatePolicy = DuplicatePolicy.FIRST,
    ): MergeResult {
        require(dexBytes.isNotEmpty()) { "no DEX inputs" }
        val seen = LinkedHashMap<String, ClassDef>()
        val skipped = mutableListOf<String>()
        val winners = LinkedHashMap<String, String>()
        for ((dexIndex, b) in dexBytes.withIndex()) {
            val dex = load(b)
            for (c in dex.classes) {
                val existing = seen[c.type]
                if (existing == null) {
                    seen[c.type] = c
                } else {
                    val keepNew = when (policy) {
                        DuplicatePolicy.FIRST -> false
                        DuplicatePolicy.LAST -> true
                        DuplicatePolicy.PREFER_LARGER -> methodCount(c) > methodCount(existing)
                    }
                    if (keepNew) {
                        seen[c.type] = c
                        winners[c.type] = "dex[$dexIndex] wins (${policy.name.lowercase()})"
                    } else {
                        skipped += c.type
                        winners.putIfAbsent(c.type, "dex[earlier] wins (${policy.name.lowercase()})")
                    }
                }
            }
        }
        val out = write(seen.values)
        val repaired = DexRepair.repair(out).first
        return MergeResult(repaired, seen.size, methodCount(seen.values), skipped.distinct(), winners)
    }

    /**
     * Merge then rebalance into as few DEX files as will fit under [methodBudget] method
     * references each (default just under the 64k limit). Classes are packed greedily in
     * descriptor order. Returns one or more parts named classes.dex, classes2.dex, …
     */
    fun mergeAndRebalance(
        dexBytes: List<ByteArray>,
        policy: DuplicatePolicy = DuplicatePolicy.FIRST,
        methodBudget: Int = DEFAULT_METHOD_BUDGET,
    ): List<SplitPart> {
        require(methodBudget in 1 until METHOD_LIMIT) { "methodBudget must be in 1..${METHOD_LIMIT - 1}" }
        val merged = merge(dexBytes, policy)
        return rebalanceByMethodLimit(merged.dex, methodBudget)
    }

    /** Pack classes from [bytes] so each part stays under [methodBudget] methods. */
    fun rebalanceByMethodLimit(bytes: ByteArray, methodBudget: Int = DEFAULT_METHOD_BUDGET): List<SplitPart> {
        require(methodBudget in 1 until METHOD_LIMIT)
        val classes = load(bytes).classes.sortedBy { it.type }
        if (classes.isEmpty()) {
            return listOf(SplitPart("classes.dex", DexRepair.repair(bytes).first, 0, 0))
        }
        val parts = mutableListOf<SplitPart>()
        var batch = mutableListOf<ClassDef>()
        var methods = 0
        var part = 1
        fun flush() {
            if (batch.isEmpty()) return
            val name = if (part == 1) "classes.dex" else "classes$part.dex"
            val m = methodCount(batch)
            parts += SplitPart(name, DexRepair.repair(write(batch)).first, batch.size, m)
            batch = mutableListOf()
            methods = 0
            part++
        }
        for (c in classes) {
            val m = methodCount(c)
            if (m > methodBudget) {
                // Oversized single class: put it alone (caller must handle ART limits).
                flush()
                batch += c
                flush()
                continue
            }
            if (batch.isNotEmpty() && methods + m > methodBudget) flush()
            batch += c
            methods += m
        }
        flush()
        return parts
    }

    /**
     * Split [bytes] into parts of at most [classesPerDex] classes (default 10_000, under the
     * historical 65k method-ish comfort zone for class counts). Names: classes.dex, classes2.dex, …
     */
    fun splitByClassCount(bytes: ByteArray, classesPerDex: Int = 10_000): List<SplitPart> {
        require(classesPerDex > 0)
        val classes = load(bytes).classes.sortedBy { it.type }
        if (classes.isEmpty()) return listOf(SplitPart("classes.dex", DexRepair.repair(bytes).first, 0, 0))
        val parts = mutableListOf<SplitPart>()
        var i = 0
        var part = 1
        while (i < classes.size) {
            val chunk = classes.subList(i, minOf(i + classesPerDex, classes.size))
            val name = if (part == 1) "classes.dex" else "classes$part.dex"
            parts += SplitPart(name, DexRepair.repair(write(chunk)).first, chunk.size, methodCount(chunk))
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
            parts += SplitPart(name, DexRepair.repair(write(batch)).first, batch.size, methodCount(batch))
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

    /** Detect duplicate class descriptors across several DEX images. */
    fun findDuplicates(dexBytes: List<ByteArray>): Map<String, List<Int>> {
        val hits = LinkedHashMap<String, MutableList<Int>>()
        for ((i, b) in dexBytes.withIndex()) {
            for (c in load(b).classes) {
                hits.getOrPut(c.type) { mutableListOf() } += i
            }
        }
        return hits.filterValues { it.size > 1 }
    }
}
