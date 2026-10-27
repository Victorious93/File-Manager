package ai.victorious.filemanager.core.apk.mtnp

import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction21c
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction31c
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.value.EncodedValue
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.value.ImmutableStringEncodedValue
import com.android.tools.smali.dexlib2.rewriter.DexRewriter
import com.android.tools.smali.dexlib2.rewriter.EncodedValueRewriter
import com.android.tools.smali.dexlib2.rewriter.InstructionRewriter
import com.android.tools.smali.dexlib2.rewriter.Rewriter
import com.android.tools.smali.dexlib2.rewriter.RewriterModule
import com.android.tools.smali.dexlib2.rewriter.Rewriters
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool

/**
 * List / replace string literals in a DEX via dexlib2 instruction + encoded-value rewriters
 * (dexlib2 3.x has no getStringRewriter). Pool-only append without a reference is stripped on rewrite.
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
            override fun getInstructionRewriter(rewriters: Rewriters): Rewriter<Instruction> {
                val base = InstructionRewriter(rewriters)
                return Rewriter { instruction ->
                    val r = base.rewrite(instruction)
                    remapStringInstruction(r, edits) ?: r
                }
            }

            override fun getEncodedValueRewriter(rewriters: Rewriters): Rewriter<EncodedValue> {
                val base = EncodedValueRewriter(rewriters)
                return Rewriter { value ->
                    val r = base.rewrite(value)
                    if (r is StringEncodedValue) {
                        val neu = edits[r.value]
                        if (neu != null) ImmutableStringEncodedValue(neu) else r
                    } else r
                }
            }
        })
        val rewritten = rewriter.dexFileRewriter.rewrite(dex)
        val pool = DexPool(Opcodes.getDefault())
        for (c in rewritten.classes) pool.internClass(c)
        val store = MemoryDataStore()
        pool.writeTo(store)
        return DexRepair.repair(store.getData()).first
    }

    private fun remapStringInstruction(instruction: Instruction, edits: Map<String, String>): Instruction? {
        if (instruction !is ReferenceInstruction) return null
        val ref = instruction.reference
        if (ref !is StringReference) return null
        val neu = edits[ref.string] ?: return null
        val newRef = ImmutableStringReference(neu)
        return when (instruction) {
            is Instruction21c -> ImmutableInstruction21c(instruction.opcode, instruction.registerA, newRef)
            is Instruction31c -> ImmutableInstruction31c(instruction.opcode, instruction.registerA, newRef)
            else -> null
        }
    }

    /** Convenience: apply a single old→new string edit. */
    fun replaceOne(bytes: ByteArray, old: String, new: String): ByteArray =
        replaceStrings(bytes, mapOf(old to new))


    /**
     * Adds [values] as live string constants by inserting (or replacing) a tiny helper class
     * whose static `<clinit>` loads each string into a field. DexPool keeps referenced strings;
     * unreferred pool-only appends are stripped on rewrite.
     *
     * @param classType e.g. `Lfm/AddedStrings;`
     */
    fun addConstantsClass(
        bytes: ByteArray,
        values: List<String>,
        classType: String = "Lfm/AddedStrings;",
    ): ByteArray {
        require(values.isNotEmpty()) { "No strings to add" }
        require(classType.startsWith("L") && classType.endsWith(";")) { "Bad class type" }
        val fields = values.mapIndexed { i, s ->
            val esc = s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
            """
    .field public static final s$i:Ljava/lang/String; = "$esc"
""".trimEnd()
        }.joinToString("\n")
        val smali = """
.class public $classType
.super Ljava/lang/Object;

$fields
""".trimIndent()
        val cls = ai.victorious.filemanager.core.apk.SmaliAssembler.assembleClass(smali)
        return DexRepair.repair(
            ai.victorious.filemanager.core.apk.SmaliAssembler.replaceClass(bytes, cls),
        ).first
    }

    /** Convenience: add a single constant via [addConstantsClass]. */
    fun addConstant(bytes: ByteArray, value: String, classType: String = "Lfm/AddedStrings;"): ByteArray =
        addConstantsClass(bytes, listOf(value), classType)

}
