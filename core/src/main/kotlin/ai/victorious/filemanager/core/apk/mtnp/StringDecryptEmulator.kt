package ai.victorious.filemanager.core.apk.mtnp

import ai.victorious.filemanager.core.apk.DexTools
import ai.victorious.filemanager.core.apk.SmaliAssembler
import ai.victorious.filemanager.core.deobf.StringDecryptionHints
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.ArrayPayload
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction21c
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction21t
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction22c
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction22t
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction31c
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Finds likely string-decryption methods (static `String f(String)` / `String f(int)` patterns
 * called with constant arguments) and safely emulates simple pure ones with a restricted
 * Dalvik interpreter: no reflection, IO, or network; instruction + wall-clock budgets; opt-in
 * only. Results can optionally be written back as `const-string` in smali via [inlineResultsInSmali].
 *
 * Built from public DEX / smali specs + dexlib2 (BSD). Never copies MT/NP Manager.
 */
object StringDecryptEmulator {
    const val DEFAULT_MAX_INSTRUCTIONS = 50_000
    const val DEFAULT_TIMEOUT_MS = 2_000L
    const val DEFAULT_MAX_DEPTH = 8
    const val DEFAULT_MAX_CALL_SITES = 200

    data class DecryptorMethod(
        val classType: String,
        val name: String,
        val params: String,
        val returnType: String,
        val callSites: Int,
        val score: Int,
        val reason: String,
    ) {
        val key: String get() = "$classType->$name($params)$returnType"
        val signature: String get() = "${DexTools.descriptorToJava(classType)}.$name($params)$returnType"
    }

    data class CallSite(val callerClass: String, val callerMethod: String, val arg: Any)

    data class EmulationResult(
        val method: DecryptorMethod,
        val arg: Any,
        val plaintext: String?,
        val error: String?,
        val instructions: Int,
    ) {
        val ok: Boolean get() = plaintext != null && error == null
    }

    data class Limits(
        val maxInstructions: Int = DEFAULT_MAX_INSTRUCTIONS,
        val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        val maxDepth: Int = DEFAULT_MAX_DEPTH,
    )

    fun findDecryptors(dexes: List<DexBackedDexFile>, minCallSites: Int = 2, limit: Int = 40): List<DecryptorMethod> {
        val hints = StringDecryptionHints.find(dexes, minCallSites = minCallSites, limit = limit)
        val fromHints = hints.mapNotNull { h ->
            // Hints use "java.name.meth(params)" without return type (always String).
            val m = Regex("""^(.*)\.([^.]+)\((.*)\)$""").matchEntire(h.signature) ?: return@mapNotNull null
            val classType = "L" + m.groupValues[1].replace('.', '/') + ";"
            DecryptorMethod(
                classType, m.groupValues[2], m.groupValues[3], "Ljava/lang/String;",
                h.callSites, h.score, h.reason,
            )
        }
        if (fromHints.isNotEmpty()) return fromHints
        val out = mutableListOf<DecryptorMethod>()
        for (dex in dexes) for (c in dex.classes) for (meth in c.methods) {
            if ((meth.accessFlags and AccessFlags.STATIC.value) == 0) continue
            if (meth.returnType != "Ljava/lang/String;") continue
            val params = meth.parameterTypes.joinToString("")
            if (params !in setOf("Ljava/lang/String;", "I", "J", "[B", "[C")) continue
            if (meth.implementation == null) continue
            out += DecryptorMethod(c.type, meth.name, params, meth.returnType, 0, if (meth.name.length <= 2) 5 else 1, "static String($params)")
        }
        return out.sortedByDescending { it.score }.take(limit)
    }

    fun collectConstantCallSites(
        dexes: List<DexBackedDexFile>,
        method: DecryptorMethod,
        limit: Int = DEFAULT_MAX_CALL_SITES,
    ): List<CallSite> {
        val want = method.key
        val sites = mutableListOf<CallSite>()
        for (dex in dexes) for (c in dex.classes) for (m in c.methods) {
            val impl = m.implementation ?: continue
            var lastString: String? = null
            var lastInt: Int? = null
            for (ins in impl.instructions) {
                when (ins.opcode) {
                    Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO -> {
                        lastString = ((ins as ReferenceInstruction).reference as StringReference).string
                        lastInt = null
                    }
                    Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST, Opcode.CONST_HIGH16 -> {
                        lastInt = (ins as NarrowLiteralInstruction).narrowLiteral
                        lastString = null
                    }
                    Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE -> {
                        val ref = (ins as ReferenceInstruction).reference as MethodReference
                        val key = "${ref.definingClass}->${ref.name}(${ref.parameterTypes.joinToString("")})${ref.returnType}"
                        if (key == want) {
                            val arg: Any? = when (method.params) {
                                "Ljava/lang/String;" -> lastString
                                "I" -> lastInt
                                else -> lastString ?: lastInt
                            }
                            if (arg != null) {
                                sites += CallSite(c.type, m.name, arg)
                                if (sites.size >= limit) return sites
                            }
                        }
                        lastString = null
                        lastInt = null
                    }
                    else -> {
                        if (ins.opcode.name.startsWith("invoke") || ins.opcode.name.startsWith("new-")) {
                            lastString = null
                            lastInt = null
                        }
                    }
                }
            }
        }
        return sites
    }

    fun emulate(
        dexes: List<DexBackedDexFile>,
        method: DecryptorMethod,
        arg: Any,
        limits: Limits = Limits(),
        cancelled: AtomicBoolean = AtomicBoolean(false),
    ): EmulationResult {
        val resolved = resolveMethod(dexes, method)
            ?: return EmulationResult(method, arg, null, "method body not found", 0)
        return try {
            val vm = Vm(dexes, limits, cancelled)
            val result = vm.invokeStatic(resolved, listOf(boxArg(arg, method.params)), depth = 0)
            when (result) {
                is String -> EmulationResult(method, arg, result, null, vm.instructions)
                null -> EmulationResult(method, arg, null, "returned null", vm.instructions)
                else -> EmulationResult(method, arg, result.toString(), null, vm.instructions)
            }
        } catch (e: EmulationException) {
            EmulationResult(method, arg, null, e.message, 0)
        } catch (e: Exception) {
            EmulationResult(method, arg, null, e.message ?: e.javaClass.simpleName, 0)
        }
    }

    fun emulateCallSites(
        dexes: List<DexBackedDexFile>,
        method: DecryptorMethod,
        limits: Limits = Limits(),
        cancelled: AtomicBoolean = AtomicBoolean(false),
    ): List<EmulationResult> {
        val sites = collectConstantCallSites(dexes, method)
        val seen = LinkedHashSet<Any>()
        val out = mutableListOf<EmulationResult>()
        for (s in sites) {
            if (!seen.add(s.arg)) continue
            out += emulate(dexes, method, s.arg, limits, cancelled)
            if (cancelled.get()) break
        }
        return out
    }

    fun inlineResultsInSmali(smali: String, method: DecryptorMethod, results: Map<Any, String>): String {
        if (results.isEmpty()) return smali
        val owner = method.classType
        val invokeRe = Regex(
            """(?m)^([ \t]*)invoke-static(?:/range)?\s+\{[^}]*\},\s*""" +
                Regex.escape("$owner->${method.name}(${method.params})Ljava/lang/String;") + """\s*$""",
        )
        val lines = smali.lines().toMutableList()
        var i = 0
        while (i < lines.size) {
            val m = invokeRe.find(lines[i])
            if (m != null) {
                var arg: Any? = null
                var constIdx = -1
                for (j in (i - 1) downTo maxOf(0, i - 8)) {
                    val cs = Regex("""const-string(?:/jumbo)?\s+v\d+,\s*"((?:\\.|[^"\\])*)"""").find(lines[j])
                    if (cs != null) {
                        arg = unescapeSmali(cs.groupValues[1]); constIdx = j; break
                    }
                    val ci = Regex("""const(?:/4|/16)?\s+v\d+,\s*(-?0x[0-9a-fA-F]+|-?\d+)""").find(lines[j])
                    if (ci != null) {
                        arg = parseIntLiteral(ci.groupValues[1]); constIdx = j; break
                    }
                }
                val plain = arg?.let { results[it] }
                if (plain != null && constIdx >= 0) {
                    val indent = m.groupValues[1]
                    var moveIdx = -1
                    var moveReg = "v0"
                    for (j in (i + 1)..minOf(lines.lastIndex, i + 3)) {
                        val mr = Regex("""^([ \t]*)move-result-object\s+(v\d+)""").find(lines[j])
                        if (mr != null) { moveIdx = j; moveReg = mr.groupValues[2]; break }
                    }
                    if (moveIdx >= 0) {
                        lines[constIdx] = "$indent# decrypted by StringDecryptEmulator"
                        lines[i] = "$indent const-string $moveReg, \"${escapeSmali(plain)}\""
                        lines[moveIdx] = "$indent# was move-result-object $moveReg"
                    }
                }
            }
            i++
        }
        return lines.joinToString("\n")
    }

    fun inlineIntoDex(dexBytes: ByteArray, method: DecryptorMethod, results: Map<Any, String>): ByteArray {
        if (results.isEmpty()) return dexBytes
        val dex = DexTools.load(dexBytes)
        val key = method.key
        val rewritten = mutableListOf<ClassDef>()
        var changed = false
        for (c in dex.classes) {
            val uses = c.methods.any { meth ->
                val impl = meth.implementation ?: return@any false
                impl.instructions.any { ins ->
                    if (ins.opcode != Opcode.INVOKE_STATIC && ins.opcode != Opcode.INVOKE_STATIC_RANGE) return@any false
                    val ref = (ins as ReferenceInstruction).reference as MethodReference
                    "${ref.definingClass}->${ref.name}(${ref.parameterTypes.joinToString("")})${ref.returnType}" == key
                }
            }
            if (!uses) { rewritten += c; continue }
            val smali = DexTools.classSmali(c)
            val neu = inlineResultsInSmali(smali, method, results)
            if (neu != smali) {
                rewritten += SmaliAssembler.assembleClass(neu)
                changed = true
            } else rewritten += c
        }
        if (!changed) return dexBytes
        return DexRepair.repair(DexMergeSplit.write(rewritten)).first
    }

    private fun boxArg(arg: Any, params: String): Any = when (params) {
        "Ljava/lang/String;" -> arg as? String ?: arg.toString()
        "I" -> when (arg) {
            is Int -> arg
            is Number -> arg.toInt()
            is String -> arg.toIntOrNull() ?: error("int arg expected")
            else -> error("int arg expected")
        }
        "J" -> when (arg) {
            is Long -> arg
            is Number -> arg.toLong()
            else -> error("long arg expected")
        }
        "[B" -> arg as? ByteArray ?: error("byte[] arg expected")
        "[C" -> arg as? CharArray ?: error("char[] arg expected")
        else -> arg
    }

    private fun resolveMethod(dexes: List<DexBackedDexFile>, method: DecryptorMethod): Method? {
        for (dex in dexes) {
            val c = dex.classes.firstOrNull { it.type == method.classType } ?: continue
            val m = c.methods.firstOrNull {
                it.name == method.name &&
                    it.parameterTypes.joinToString("") == method.params &&
                    it.returnType == method.returnType &&
                    (it.accessFlags and AccessFlags.STATIC.value) != 0
            }
            if (m != null) return m
        }
        return null
    }

    private class EmulationException(msg: String) : RuntimeException(msg)
    private class Uninitialized(val type: String)

    private class Vm(
        private val dexes: List<DexBackedDexFile>,
        private val limits: Limits,
        private val cancelled: AtomicBoolean,
    ) {
        var instructions = 0
        private val started = System.nanoTime()

        fun invokeStatic(method: Method, args: List<Any?>, depth: Int): Any? {
            if (depth > limits.maxDepth) throw EmulationException("max call depth exceeded")
            val impl = method.implementation ?: throw EmulationException("abstract/native method")
            val regs = arrayOfNulls<Any?>(impl.registerCount)
            val paramRegs = method.parameterTypes.sumOf { widthOf(it.toString()) }
            var p = impl.registerCount - paramRegs
            for ((i, t) in method.parameterTypes.withIndex()) {
                regs[p] = args.getOrNull(i)
                p += widthOf(t.toString())
            }
            val code = impl.instructions.toList()
            val addrIndex = HashMap<Int, Int>()
            var addr = 0
            for ((idx, ins) in code.withIndex()) {
                addrIndex[addr] = idx
                addr += ins.codeUnits
            }
            var pc = 0
            var pendingResult: Any? = null
            while (pc < code.size) {
                tick()
                val ins = code[pc]
                when (ins.opcode) {
                    Opcode.NOP, Opcode.RETURN_VOID -> return null
                    Opcode.RETURN, Opcode.RETURN_OBJECT, Opcode.RETURN_WIDE ->
                        return regs[(ins as OneRegisterInstruction).registerA]
                    Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST, Opcode.CONST_HIGH16 ->
                        regs[(ins as OneRegisterInstruction).registerA] = (ins as NarrowLiteralInstruction).narrowLiteral
                    Opcode.CONST_WIDE_16, Opcode.CONST_WIDE_32, Opcode.CONST_WIDE, Opcode.CONST_WIDE_HIGH16 ->
                        regs[(ins as OneRegisterInstruction).registerA] = (ins as WideLiteralInstruction).wideLiteral
                    Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO -> {
                        val r = when (ins) {
                            is Instruction21c -> ins.registerA
                            is Instruction31c -> ins.registerA
                            else -> (ins as OneRegisterInstruction).registerA
                        }
                        regs[r] = ((ins as ReferenceInstruction).reference as StringReference).string
                    }
                    Opcode.CONST_CLASS -> throw EmulationException("const-class not allowed")
                    Opcode.MOVE, Opcode.MOVE_FROM16, Opcode.MOVE_16,
                    Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16,
                    Opcode.MOVE_WIDE, Opcode.MOVE_WIDE_FROM16, Opcode.MOVE_WIDE_16 -> {
                        val t = ins as TwoRegisterInstruction
                        regs[t.registerA] = regs[t.registerB]
                    }
                    Opcode.MOVE_RESULT, Opcode.MOVE_RESULT_OBJECT, Opcode.MOVE_RESULT_WIDE -> {
                        regs[(ins as OneRegisterInstruction).registerA] = pendingResult
                        pendingResult = null
                    }
                    Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32 -> {
                        pc = targetIndex(addrIndex, code, pc, ins); continue
                    }
                    Opcode.IF_EQZ, Opcode.IF_NEZ, Opcode.IF_LTZ, Opcode.IF_GEZ, Opcode.IF_GTZ, Opcode.IF_LEZ -> {
                        val t = ins as Instruction21t
                        val v = intVal(regs[t.registerA])
                        val take = when (ins.opcode) {
                            Opcode.IF_EQZ -> v == 0; Opcode.IF_NEZ -> v != 0
                            Opcode.IF_LTZ -> v < 0; Opcode.IF_GEZ -> v >= 0
                            Opcode.IF_GTZ -> v > 0; Opcode.IF_LEZ -> v <= 0
                            else -> false
                        }
                        if (take) { pc = targetIndex(addrIndex, code, pc, ins); continue }
                    }
                    Opcode.IF_EQ, Opcode.IF_NE, Opcode.IF_LT, Opcode.IF_GE, Opcode.IF_GT, Opcode.IF_LE -> {
                        val t = ins as Instruction22t
                        val a = intVal(regs[t.registerA]); val b = intVal(regs[t.registerB])
                        val take = when (ins.opcode) {
                            Opcode.IF_EQ -> a == b; Opcode.IF_NE -> a != b
                            Opcode.IF_LT -> a < b; Opcode.IF_GE -> a >= b
                            Opcode.IF_GT -> a > b; Opcode.IF_LE -> a <= b
                            else -> false
                        }
                        if (take) { pc = targetIndex(addrIndex, code, pc, ins); continue }
                    }
                    Opcode.ADD_INT, Opcode.SUB_INT, Opcode.MUL_INT, Opcode.DIV_INT, Opcode.REM_INT,
                    Opcode.AND_INT, Opcode.OR_INT, Opcode.XOR_INT, Opcode.SHL_INT, Opcode.SHR_INT, Opcode.USHR_INT -> {
                        val t = ins as ThreeRegisterInstruction
                        regs[t.registerA] = binOp(ins.opcode, intVal(regs[t.registerB]), intVal(regs[t.registerC]))
                    }
                    Opcode.ADD_INT_2ADDR, Opcode.SUB_INT_2ADDR, Opcode.MUL_INT_2ADDR, Opcode.DIV_INT_2ADDR,
                    Opcode.REM_INT_2ADDR, Opcode.AND_INT_2ADDR, Opcode.OR_INT_2ADDR, Opcode.XOR_INT_2ADDR,
                    Opcode.SHL_INT_2ADDR, Opcode.SHR_INT_2ADDR, Opcode.USHR_INT_2ADDR -> {
                        val t = ins as TwoRegisterInstruction
                        regs[t.registerA] = binOp2Addr(ins.opcode, intVal(regs[t.registerA]), intVal(regs[t.registerB]))
                    }
                    Opcode.ADD_INT_LIT8, Opcode.RSUB_INT_LIT8, Opcode.MUL_INT_LIT8, Opcode.DIV_INT_LIT8,
                    Opcode.REM_INT_LIT8, Opcode.AND_INT_LIT8, Opcode.OR_INT_LIT8, Opcode.XOR_INT_LIT8,
                    Opcode.SHL_INT_LIT8, Opcode.SHR_INT_LIT8, Opcode.USHR_INT_LIT8,
                    Opcode.ADD_INT_LIT16, Opcode.RSUB_INT, Opcode.MUL_INT_LIT16, Opcode.DIV_INT_LIT16,
                    Opcode.REM_INT_LIT16, Opcode.AND_INT_LIT16, Opcode.OR_INT_LIT16, Opcode.XOR_INT_LIT16 -> {
                        val t = ins as TwoRegisterInstruction
                        val lit = (ins as NarrowLiteralInstruction).narrowLiteral
                        regs[t.registerA] = binOpLit(ins.opcode, intVal(regs[t.registerB]), lit)
                    }
                    Opcode.NEG_INT, Opcode.NOT_INT -> {
                        val t = ins as TwoRegisterInstruction
                        val a = intVal(regs[t.registerB])
                        regs[t.registerA] = if (ins.opcode == Opcode.NEG_INT) -a else a.inv()
                    }
                    Opcode.INT_TO_BYTE, Opcode.INT_TO_CHAR, Opcode.INT_TO_SHORT -> {
                        val t = ins as TwoRegisterInstruction
                        val a = intVal(regs[t.registerB])
                        regs[t.registerA] = when (ins.opcode) {
                            Opcode.INT_TO_BYTE -> a.toByte().toInt()
                            Opcode.INT_TO_CHAR -> a.toChar().code
                            else -> a.toShort().toInt()
                        }
                    }
                    Opcode.NEW_ARRAY -> {
                        val t = ins as Instruction22c
                        val size = intVal(regs[t.registerB])
                        if (size < 0 || size > 1_000_000) throw EmulationException("array too large")
                        regs[t.registerA] = when ((t.reference as TypeReference).type) {
                            "[B" -> ByteArray(size); "[C" -> CharArray(size)
                            "[I" -> IntArray(size); "[Z" -> BooleanArray(size)
                            else -> arrayOfNulls<Any?>(size)
                        }
                    }
                    Opcode.ARRAY_LENGTH -> {
                        val t = ins as TwoRegisterInstruction
                        regs[t.registerA] = arrayLength(regs[t.registerB])
                    }
                    Opcode.AGET_BYTE, Opcode.AGET_CHAR, Opcode.AGET, Opcode.AGET_BOOLEAN, Opcode.AGET_SHORT,
                    Opcode.AGET_WIDE, Opcode.AGET_OBJECT -> {
                        val t = ins as ThreeRegisterInstruction
                        regs[t.registerA] = arrayGet(regs[t.registerB], intVal(regs[t.registerC]))
                    }
                    Opcode.APUT_BYTE, Opcode.APUT_CHAR, Opcode.APUT, Opcode.APUT_BOOLEAN, Opcode.APUT_SHORT,
                    Opcode.APUT_WIDE, Opcode.APUT_OBJECT -> {
                        val t = ins as ThreeRegisterInstruction
                        arrayPut(regs[t.registerB], intVal(regs[t.registerC]), regs[t.registerA])
                    }
                    Opcode.FILL_ARRAY_DATA -> { /* payload skipped */ }
                    Opcode.CHECK_CAST -> { /* allow */ }
                    Opcode.INSTANCE_OF -> {
                        val t = ins as Instruction22c
                        regs[t.registerA] = if (regs[t.registerB] != null) 1 else 0
                    }
                    Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE,
                    Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE,
                    Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE ->
                        pendingResult = handleInvoke(ins, regs, depth)
                    Opcode.SGET_OBJECT, Opcode.SGET, Opcode.SGET_BYTE, Opcode.SGET_CHAR, Opcode.SGET_SHORT,
                    Opcode.SGET_BOOLEAN, Opcode.SGET_WIDE,
                    Opcode.IGET_OBJECT, Opcode.IGET, Opcode.IPUT_OBJECT, Opcode.IPUT,
                    Opcode.SPUT_OBJECT, Opcode.SPUT ->
                        throw EmulationException("field access not allowed (${ins.opcode})")
                    Opcode.NEW_INSTANCE -> {
                        val t = ins as Instruction21c
                        val type = (t.reference as TypeReference).type
                        if (type != "Ljava/lang/String;" && type != "Ljava/lang/StringBuilder;") {
                            throw EmulationException("new-instance not allowed: $type")
                        }
                        regs[t.registerA] = Uninitialized(type)
                    }
                    Opcode.THROW, Opcode.MONITOR_ENTER, Opcode.MONITOR_EXIT ->
                        throw EmulationException("${ins.opcode} not allowed")
                    else -> {
                        if (ins !is ArrayPayload && !ins.opcode.format.name.contains("Payload")) {
                            throw EmulationException("unsupported opcode ${ins.opcode}")
                        }
                    }
                }
                pc++
            }
            throw EmulationException("fell off method end")
        }

        private fun handleInvoke(ins: Instruction, regs: Array<Any?>, depth: Int): Any? {
            val ref = (ins as ReferenceInstruction).reference as MethodReference
            val owner = ref.definingClass
            val name = ref.name
            val params = ref.parameterTypes.joinToString("")
            val args = invokeArgs(ins, regs)
            if (owner == "Ljava/lang/String;") {
                val r = invokeString(name, params, args)
                if (name == "<init>" && args.isNotEmpty()) {
                    // Replace uninitialized instance register with constructed String.
                    when (ins) {
                        is FiveRegisterInstruction -> regs[ins.registerC] = r
                        is RegisterRangeInstruction -> regs[ins.startRegister] = r
                        is Instruction35c -> regs[ins.registerC] = r
                    }
                }
                return r
            }
            if (owner == "Ljava/lang/StringBuilder;") {
                val r = invokeStringBuilder(name, params, args)
                if (name == "<init>" && args.isNotEmpty()) {
                    when (ins) {
                        is FiveRegisterInstruction -> regs[ins.registerC] = r
                        is RegisterRangeInstruction -> regs[ins.startRegister] = r
                        is Instruction35c -> regs[ins.registerC] = r
                    }
                }
                return r
            }
            if (owner == "Ljava/lang/Integer;" && name == "valueOf" && params == "I") return args[0]
            if (ins.opcode == Opcode.INVOKE_STATIC || ins.opcode == Opcode.INVOKE_STATIC_RANGE) {
                if (isForbiddenOwner(owner)) throw EmulationException("invoke blocked: $owner")
                val method = DecryptorMethod(owner, name, params, ref.returnType, 0, 0, "")
                val resolved = resolveMethod(dexes, method) ?: throw EmulationException("missing $owner->$name")
                if ((resolved.accessFlags and AccessFlags.STATIC.value) == 0) {
                    throw EmulationException("non-static invoke blocked")
                }
                return invokeStatic(resolved, args, depth + 1)
            }
            throw EmulationException("invoke blocked: $owner->$name")
        }

        private fun invokeArgs(ins: Instruction, regs: Array<Any?>): List<Any?> = when (ins) {
            is FiveRegisterInstruction -> {
                val ids = intArrayOf(ins.registerC, ins.registerD, ins.registerE, ins.registerF, ins.registerG)
                (0 until ins.registerCount).map { regs[ids[it]] }
            }
            is RegisterRangeInstruction -> (0 until ins.registerCount).map { regs[ins.startRegister + it] }
            is Instruction35c -> {
                val ids = intArrayOf(ins.registerC, ins.registerD, ins.registerE, ins.registerF, ins.registerG)
                (0 until ins.registerCount).map { regs[ids[it]] }
            }
            else -> emptyList()
        }

        private fun invokeString(name: String, params: String, args: List<Any?>): Any? = when {
            name == "<init>" && params == "[B" -> String(args[1] as ByteArray)
            name == "<init>" && params == "[BLjava/lang/String;" ->
                String(args[1] as ByteArray, Charset.forName(args[2] as String))
            name == "<init>" && params == "[C" -> String(args[1] as CharArray)
            name == "<init>" && params.isEmpty() -> ""
            name == "<init>" && params == "Ljava/lang/String;" -> args[1] as String
            name == "length" && params.isEmpty() -> (args[0] as String).length
            name == "charAt" && params == "I" -> (args[0] as String)[args[1] as Int].code
            name == "substring" && params == "I" -> (args[0] as String).substring(args[1] as Int)
            name == "substring" && params == "II" -> (args[0] as String).substring(args[1] as Int, args[2] as Int)
            name == "getBytes" && params.isEmpty() -> (args[0] as String).toByteArray(Charsets.UTF_8)
            name == "getBytes" && params == "Ljava/lang/String;" ->
                (args[0] as String).toByteArray(Charset.forName(args[1] as String))
            name == "toCharArray" && params.isEmpty() -> (args[0] as String).toCharArray()
            name == "valueOf" && params == "I" -> (args[0] as Int).toString()
            name == "valueOf" && params == "C" -> (args[0] as Int).toChar().toString()
            name == "valueOf" && params == "Ljava/lang/Object;" -> args[0]?.toString() ?: "null"
            name == "equals" && params == "Ljava/lang/Object;" -> if ((args[0] as String) == args[1]) 1 else 0
            name == "isEmpty" && params.isEmpty() -> if ((args[0] as String).isEmpty()) 1 else 0
            else -> throw EmulationException("String.$name($params) not whitelisted")
        }

        private fun invokeStringBuilder(name: String, params: String, args: List<Any?>): Any? = when {
            name == "<init>" && params.isEmpty() -> StringBuilder()
            name == "<init>" && params == "Ljava/lang/String;" -> StringBuilder(args[1] as String)
            name == "<init>" && params == "I" -> StringBuilder(args[1] as Int)
            name == "append" && params == "Ljava/lang/String;" -> (args[0] as StringBuilder).append(args[1] as String?)
            name == "append" && params == "C" -> (args[0] as StringBuilder).append((args[1] as Int).toChar())
            name == "append" && params == "I" -> (args[0] as StringBuilder).append(args[1] as Int)
            name == "append" && params == "Ljava/lang/Object;" -> (args[0] as StringBuilder).append(args[1])
            name == "toString" && params.isEmpty() -> (args[0] as StringBuilder).toString()
            name == "length" && params.isEmpty() -> (args[0] as StringBuilder).length
            else -> throw EmulationException("StringBuilder.$name($params) not whitelisted")
        }

        private fun isForbiddenOwner(owner: String): Boolean {
            val bad = listOf(
                "Ljava/lang/reflect/", "Ljava/lang/Class;", "Ljava/lang/ClassLoader;",
                "Ljava/lang/Runtime;", "Ljava/lang/System;", "Ljava/lang/Thread;",
                "Ljava/io/", "Ljava/net/", "Landroid/", "Ldalvik/", "Lsun/", "Ljdk/",
            )
            return bad.any { owner == it || owner.startsWith(it) }
        }

        private fun tick() {
            if (cancelled.get()) throw EmulationException("cancelled")
            instructions++
            if (instructions > limits.maxInstructions) throw EmulationException("instruction budget exceeded")
            val elapsed = (System.nanoTime() - started) / 1_000_000L
            if (elapsed > limits.timeoutMs) throw EmulationException("timeout after ${elapsed}ms")
        }

        private fun targetIndex(addrIndex: Map<Int, Int>, code: List<Instruction>, pc: Int, ins: Instruction): Int {
            val offset = (ins as OffsetInstruction).codeOffset
            var a = 0
            for (i in 0 until pc) a += code[i].codeUnits
            return addrIndex[a + offset] ?: throw EmulationException("bad branch target")
        }
    }

    private fun widthOf(type: String): Int = if (type == "J" || type == "D") 2 else 1
    private fun intVal(v: Any?): Int = when (v) {
        null -> 0; is Int -> v; is Boolean -> if (v) 1 else 0
        is Byte -> v.toInt(); is Short -> v.toInt(); is Char -> v.code; is Long -> v.toInt()
        else -> throw EmulationException("expected int, got ${v.javaClass.simpleName}")
    }
    private fun binOp(op: Opcode, a: Int, b: Int): Int = when (op) {
        Opcode.ADD_INT -> a + b; Opcode.SUB_INT -> a - b; Opcode.MUL_INT -> a * b
        Opcode.DIV_INT -> a / b; Opcode.REM_INT -> a % b; Opcode.AND_INT -> a and b
        Opcode.OR_INT -> a or b; Opcode.XOR_INT -> a xor b; Opcode.SHL_INT -> a shl b
        Opcode.SHR_INT -> a shr b; Opcode.USHR_INT -> a ushr b
        else -> throw EmulationException("binop $op")
    }
    private fun binOp2Addr(op: Opcode, a: Int, b: Int): Int = when (op) {
        Opcode.ADD_INT_2ADDR -> a + b; Opcode.SUB_INT_2ADDR -> a - b; Opcode.MUL_INT_2ADDR -> a * b
        Opcode.DIV_INT_2ADDR -> a / b; Opcode.REM_INT_2ADDR -> a % b; Opcode.AND_INT_2ADDR -> a and b
        Opcode.OR_INT_2ADDR -> a or b; Opcode.XOR_INT_2ADDR -> a xor b; Opcode.SHL_INT_2ADDR -> a shl b
        Opcode.SHR_INT_2ADDR -> a shr b; Opcode.USHR_INT_2ADDR -> a ushr b
        else -> throw EmulationException("binop2 $op")
    }
    private fun binOpLit(op: Opcode, a: Int, lit: Int): Int = when (op) {
        Opcode.ADD_INT_LIT8, Opcode.ADD_INT_LIT16 -> a + lit
        Opcode.RSUB_INT_LIT8, Opcode.RSUB_INT -> lit - a
        Opcode.MUL_INT_LIT8, Opcode.MUL_INT_LIT16 -> a * lit
        Opcode.DIV_INT_LIT8, Opcode.DIV_INT_LIT16 -> a / lit
        Opcode.REM_INT_LIT8, Opcode.REM_INT_LIT16 -> a % lit
        Opcode.AND_INT_LIT8, Opcode.AND_INT_LIT16 -> a and lit
        Opcode.OR_INT_LIT8, Opcode.OR_INT_LIT16 -> a or lit
        Opcode.XOR_INT_LIT8, Opcode.XOR_INT_LIT16 -> a xor lit
        Opcode.SHL_INT_LIT8 -> a shl lit; Opcode.SHR_INT_LIT8 -> a shr lit; Opcode.USHR_INT_LIT8 -> a ushr lit
        else -> throw EmulationException("litop $op")
    }
    private fun arrayLength(arr: Any?): Int = when (arr) {
        is ByteArray -> arr.size; is CharArray -> arr.size; is IntArray -> arr.size
        is BooleanArray -> arr.size; is Array<*> -> arr.size
        else -> throw EmulationException("not an array")
    }
    private fun arrayGet(arr: Any?, index: Int): Any? = when (arr) {
        is ByteArray -> arr[index].toInt() and 0xff; is CharArray -> arr[index].code
        is IntArray -> arr[index]; is BooleanArray -> if (arr[index]) 1 else 0
        is Array<*> -> arr[index]; else -> throw EmulationException("aget on ${arr?.javaClass}")
    }
    private fun arrayPut(arr: Any?, index: Int, value: Any?) {
        when (arr) {
            is ByteArray -> arr[index] = (value as Int).toByte()
            is CharArray -> arr[index] = (value as Int).toChar()
            is IntArray -> arr[index] = value as Int
            is BooleanArray -> arr[index] = (value as Int) != 0
            is Array<*> -> {
                @Suppress("UNCHECKED_CAST")
                val a = arr as Array<Any?>
                a[index] = value
            }
            else -> throw EmulationException("aput on ${arr?.javaClass}")
        }
    }

    private fun escapeSmali(s: String): String = buildString {
        for (c in s) when (c) {
            '\\' -> append("\\\\"); '"' -> append("\\\"")
            '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (c.code < 0x20 || c.code > 0x7e) append("\\u%04x".format(c.code)) else append(c)
        }
    }
    private fun unescapeSmali(s: String): String = buildString {
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> { append('\n'); i += 2 }; 'r' -> { append('\r'); i += 2 }
                    't' -> { append('\t'); i += 2 }; '\\' -> { append('\\'); i += 2 }
                    '"' -> { append('"'); i += 2 }
                    'u' -> { append(s.substring(i + 2, i + 6).toInt(16).toChar()); i += 6 }
                    else -> { append(s[i + 1]); i += 2 }
                }
            } else { append(s[i]); i++ }
        }
    }
    private fun parseIntLiteral(s: String): Int =
        if (s.contains("0x", ignoreCase = true))
            s.replace("0x", "", ignoreCase = true).toLong(16).toInt()
        else s.toInt()
}
