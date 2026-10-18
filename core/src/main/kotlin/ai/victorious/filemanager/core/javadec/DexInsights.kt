package ai.victorious.filemanager.core.javadec

import ai.victorious.filemanager.core.apk.DexTools
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/** String-constant extraction and dex↔dex comparison — both cheap, dexlib2-backed, pure JVM. */
object DexInsights {

    /** Unique string literals referenced by `const-string` instructions, sorted. */
    fun stringConstants(dex: DexBackedDexFile, limit: Int = 50_000): List<String> {
        val out = LinkedHashSet<String>()
        loop@ for (cls in dex.classes) {
            for (m in cls.methods) {
                val impl = m.implementation ?: continue
                for (insn in impl.instructions) {
                    if (insn is ReferenceInstruction) {
                        val ref = insn.reference
                        if (ref is StringReference) {
                            out.add(ref.string)
                            if (out.size >= limit) break@loop
                        }
                    }
                }
            }
        }
        return out.sorted()
    }

    data class SetDiff(val onlyInA: List<String>, val onlyInB: List<String>, val common: Int) {
        val identical: Boolean get() = onlyInA.isEmpty() && onlyInB.isEmpty()
    }

    private fun diff(a: Set<String>, b: Set<String>) =
        SetDiff((a - b).sorted(), (b - a).sorted(), a.intersect(b).size)

    /** Compares the class lists (by descriptor) of two dex files. */
    fun compareClasses(a: DexBackedDexFile, b: DexBackedDexFile): SetDiff =
        diff(a.classes.map { it.type }.toSet(), b.classes.map { it.type }.toSet())

    /** Compares fully-qualified method signatures (`Lowner;->name(params)ret`). */
    fun compareMethods(a: DexBackedDexFile, b: DexBackedDexFile): SetDiff =
        diff(methodSigs(a), methodSigs(b))

    private fun methodSigs(dex: DexBackedDexFile): Set<String> {
        val out = HashSet<String>()
        for (cls in dex.classes) for (m in cls.methods) {
            out.add("${cls.type}->${DexTools.methodDescriptor(m)}")
        }
        return out
    }
}
