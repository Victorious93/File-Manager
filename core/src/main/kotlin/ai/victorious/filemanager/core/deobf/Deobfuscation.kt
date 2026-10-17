package ai.victorious.filemanager.core.deobf

import ai.victorious.filemanager.core.apk.DexTools
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/**
 * ProGuard / R8 `mapping.txt` (original -> obfuscated). Parsed into reverse maps keyed by the
 * obfuscated names that appear in a DEX, so smali / class names can be shown deobfuscated.
 */
class ProguardMapping private constructor(
    /** obfuscated descriptor (La/b;) -> original descriptor (Lcom/x/Foo;) */
    val classes: Map<String, String>,
    /** obfuscated class descriptor -> (obfuscated field name -> original name) */
    private val fields: Map<String, Map<String, String>>,
    /** obfuscated class descriptor -> (obfuscated name + obfuscated param descriptors -> original name) */
    private val methods: Map<String, Map<String, String>>,
) {
    fun classFor(obfDescriptor: String): String = classes[obfDescriptor] ?: obfDescriptor
    fun fieldFor(obfClass: String, obfName: String): String = fields[obfClass]?.get(obfName) ?: obfName
    fun methodFor(obfClass: String, obfName: String, obfParams: String): String =
        methods[obfClass]?.get(obfName + "(" + obfParams + ")") ?: obfName

    val classCount: Int get() = classes.size

    /** Rewrites class descriptors, member references and declarations in smali text. */
    fun deobfuscateSmali(smali: String): String {
        val current = CLASS_LINE.find(smali)?.groupValues?.get(1)
        val out = StringBuilder(smali.length)
        for (line in smali.lineSequence()) {
            var l = line
            // Declarations inside the class being viewed (names before descriptors get rewritten).
            if (current != null) {
                METHOD_DECL.find(l)?.let { m ->
                    val name = m.groupValues[2]; val params = m.groupValues[3]
                    val orig = methodFor(current, name, params)
                    if (orig != name) l = l.replaceRange(m.groups[2]!!.range, orig)
                }
                FIELD_DECL.find(l)?.let { m ->
                    val name = m.groupValues[2]
                    val orig = fieldFor(current, name)
                    if (orig != name) l = l.replaceRange(m.groups[2]!!.range, orig)
                }
            }
            // References: Lowner;->name(params)ret  and  Lowner;->name:type
            l = METHOD_REF.replace(l) { m ->
                val owner = m.groupValues[1]; val name = m.groupValues[2]; val params = m.groupValues[3]
                "$owner->${methodFor(owner, name, params)}($params"
            }
            l = FIELD_REF.replace(l) { m ->
                val owner = m.groupValues[1]; val name = m.groupValues[2]
                "$owner->${fieldFor(owner, name)}:"
            }
            l = DESCRIPTOR.replace(l) { m -> classFor(m.value) }
            out.append(l).append('\n')
        }
        if (!smali.endsWith("\n") && out.isNotEmpty()) out.setLength(out.length - 1)
        return out.toString()
    }

    companion object {
        private val CLASS_LINE = Regex("""^\.class[^\n]*?(L[^;\s]+;)""", RegexOption.MULTILINE)
        private val METHOD_DECL = Regex("""^(\s*\.method\s+(?:[a-z\-]+\s+)*)([^\s(]+)\(([^)]*)\)""")
        private val FIELD_DECL = Regex("""^(\s*\.field\s+(?:[a-z\-]+\s+)*)([^\s:]+):""")
        private val METHOD_REF = Regex("""(L[^;\s]+;)->([^\s(:]+)\(([^)]*)""")
        private val FIELD_REF = Regex("""(L[^;\s]+;)->([^\s(:]+):""")
        private val DESCRIPTOR = Regex("""L[A-Za-z0-9_$/\-]+;""")
        private val CLASS_MAP = Regex("""^(\S+) -> (\S+):$""")
        private val MEMBER_MAP = Regex("""^\s+(?:\d+:\d+:)?(\S+) ([^\s(]+)(\(([^)]*)\))?(?::\d+:\d+)? -> (\S+)$""")

        fun javaToDescriptor(t: String): String {
            var base = t.trim(); var dims = 0
            while (base.endsWith("[]")) { dims++; base = base.dropLast(2) }
            val d = when (base) {
                "void" -> "V"; "boolean" -> "Z"; "byte" -> "B"; "char" -> "C"; "short" -> "S"
                "int" -> "I"; "long" -> "J"; "float" -> "F"; "double" -> "D"
                else -> "L" + base.replace('.', '/') + ";"
            }
            return "[".repeat(dims) + d
        }

        fun parse(text: String): ProguardMapping {
            data class RawMethod(val obfClassOrig: String, val origName: String, val params: List<String>, val obfName: String)
            val origToObf = HashMap<String, String>() // original descriptor -> obfuscated descriptor
            val classes = HashMap<String, String>()
            val fields = HashMap<String, HashMap<String, String>>()
            val rawMethods = mutableListOf<Pair<String, RawMethod>>() // obf class desc -> method
            var currentObf: String? = null
            for (line in text.lineSequence()) {
                if (line.isBlank() || line.trimStart().startsWith("#")) continue
                val cm = CLASS_MAP.find(line)
                if (cm != null && !line.startsWith(" ")) {
                    val orig = javaToDescriptor(cm.groupValues[1]); val obf = javaToDescriptor(cm.groupValues[2])
                    classes[obf] = orig; origToObf[orig] = obf; currentObf = obf
                    continue
                }
                val owner = currentObf ?: continue
                val mm = MEMBER_MAP.find(line) ?: continue
                val origName = mm.groupValues[2]; val obfName = mm.groupValues[5]
                if (mm.groupValues[3].isEmpty()) {
                    fields.getOrPut(owner) { HashMap() }[obfName] = origName
                } else {
                    val params = mm.groupValues[4].split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    rawMethods += owner to RawMethod(owner, origName, params, obfName)
                }
            }
            // Smali references use *obfuscated* parameter types: map each original type through origToObf.
            val methods = HashMap<String, HashMap<String, String>>()
            for ((owner, m) in rawMethods) {
                val obfParams = m.params.joinToString("") { p ->
                    val d = javaToDescriptor(p)
                    val dims = d.takeWhile { it == '[' }
                    val base = d.drop(dims.length)
                    dims + (origToObf[base] ?: base)
                }
                methods.getOrPut(owner) { HashMap() }[m.obfName + "(" + obfParams + ")"] = m.origName
            }
            return ProguardMapping(classes, fields, methods)
        }
    }
}

enum class DexHitKind { CLASS, METHOD, STRING }

data class DexHit(val kind: DexHitKind, val classDescriptor: String, val detail: String, val dexName: String)

/** Class / method / string search straight from DEX (no disassembly to disk needed). */
object DexSearch {
    fun search(
        dexes: List<Pair<String, DexBackedDexFile>>,
        query: String,
        kinds: Set<DexHitKind> = DexHitKind.values().toSet(),
        mapping: ProguardMapping? = null,
        limit: Int = 500,
    ): List<DexHit> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val out = ArrayList<DexHit>()
        for ((name, dex) in dexes) {
            for (c in dex.classes) {
                val shown = mapping?.classFor(c.type) ?: c.type
                if (DexHitKind.CLASS in kinds &&
                    (DexTools.descriptorToJava(shown).lowercase().contains(q) || DexTools.descriptorToJava(c.type).lowercase().contains(q))
                ) {
                    out += DexHit(DexHitKind.CLASS, c.type, DexTools.descriptorToJava(shown), name)
                }
                if (DexHitKind.METHOD in kinds) for (m in c.methods) {
                    val params = m.parameterTypes.joinToString("")
                    val orig = mapping?.methodFor(c.type, m.name, params) ?: m.name
                    if (m.name.lowercase().contains(q) || orig.lowercase().contains(q)) {
                        out += DexHit(DexHitKind.METHOD, c.type, "${DexTools.descriptorToJava(shown)}.$orig(${params})${m.returnType}", name)
                    }
                    if (out.size >= limit) return out
                }
                if (DexHitKind.STRING in kinds) for (m in c.methods) {
                    val impl = m.implementation ?: continue
                    for (ins in impl.instructions) {
                        if (ins.opcode != Opcode.CONST_STRING && ins.opcode != Opcode.CONST_STRING_JUMBO) continue
                        val s = ((ins as ReferenceInstruction).reference as StringReference).string
                        if (s.lowercase().contains(q)) {
                            out += DexHit(DexHitKind.STRING, c.type, "\"${s.take(120)}\" in ${m.name}", name)
                            if (out.size >= limit) return out
                        }
                    }
                }
                if (out.size >= limit) return out
            }
        }
        return out
    }
}

data class DecryptionHint(
    val method: String,
    val signature: String,
    val callSites: Int,
    val score: Int,
    val reason: String,
)

/**
 * Heuristics for string-decryption routines: static methods returning String from a
 * String / byte[] / int / char[] argument that are invoked from many places, especially
 * right after a const-string that looks encoded (base64/hex/high entropy). Hints only —
 * nothing is executed.
 */
object StringDecryptionHints {
    private val CANDIDATE_PARAMS = setOf("Ljava/lang/String;", "[B", "I", "[C", "[I", "J")

    fun find(dexes: List<DexBackedDexFile>, minCallSites: Int = 3, limit: Int = 30): List<DecryptionHint> {
        val candidates = HashMap<String, String>() // key -> signature
        for (dex in dexes) for (c in dex.classes) for (m in c.methods) {
            val isStatic = (m.accessFlags and 0x0008) != 0
            if (!isStatic || m.returnType != "Ljava/lang/String;") continue
            val params = m.parameterTypes.map { it.toString() }
            if (params.isEmpty() || params.size > 3 || params.any { it !in CANDIDATE_PARAMS }) continue
            candidates[key(c.type, m.name, params.joinToString(""))] = "${DexTools.descriptorToJava(c.type)}.${m.name}(${params.joinToString("")})"
        }
        if (candidates.isEmpty()) return emptyList()
        val calls = HashMap<String, Int>(); val encodedArgs = HashMap<String, Int>()
        for (dex in dexes) for (c in dex.classes) for (m in c.methods) {
            val impl = m.implementation ?: continue
            var lastString: String? = null
            for (ins in impl.instructions) {
                when (ins.opcode) {
                    Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO ->
                        lastString = ((ins as ReferenceInstruction).reference as StringReference).string
                    Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE -> {
                        val ref = (ins as ReferenceInstruction).reference as MethodReference
                        val k = key(ref.definingClass, ref.name, ref.parameterTypes.joinToString(""))
                        if (k in candidates) {
                            calls[k] = (calls[k] ?: 0) + 1
                            if (lastString != null && looksEncoded(lastString)) encodedArgs[k] = (encodedArgs[k] ?: 0) + 1
                        }
                        lastString = null
                    }
                    else -> Unit
                }
            }
        }
        return calls.filter { it.value >= minCallSites }.map { (k, n) ->
            val enc = encodedArgs[k] ?: 0
            val sig = candidates.getValue(k)
            val shortName = sig.substringBefore('(').substringAfterLast('.')
            val score = n + enc * 3 + (if (shortName.length <= 2) 5 else 0)
            DecryptionHint(
                method = sig.substringBefore('('),
                signature = sig,
                callSites = n,
                score = score,
                reason = buildString {
                    append("static String ← (${sig.substringAfter('(').dropLast(1)}), $n call site(s)")
                    if (enc > 0) append(", $enc with encoded-looking const-string")
                    if (shortName.length <= 2) append(", obfuscated name")
                },
            )
        }.sortedByDescending { it.score }.take(limit)
    }

    private fun key(cls: String, name: String, params: String) = "$cls->$name($params)"

    /** Base64/hex-ish or high-entropy literal of some length. */
    fun looksEncoded(s: String): Boolean {
        if (s.length < 8) return false
        if (Regex("^[A-Za-z0-9+/=_-]{12,}$").matches(s) && s.any { it.isDigit() } && s.any { it.isLetter() }) return true
        if (Regex("^[0-9a-fA-F]{16,}$").matches(s)) return true
        val freq = s.groupingBy { it }.eachCount().values
        val entropy = -freq.sumOf { val p = it.toDouble() / s.length; p * Math.log(p) / Math.log(2.0) }
        return entropy > 4.2 || s.count { it.code !in 32..126 } > s.length / 3
    }
}

/** Writes one `.smali` per class (path from the de-obfuscated name when a mapping is given). */
object SmaliExport {
    fun export(
        dexes: List<DexBackedDexFile>,
        outDir: java.io.File,
        mapping: ProguardMapping? = null,
        isCancelled: () -> Boolean = { false },
        onProgress: (Int) -> Unit = {},
    ): Int {
        val root = outDir.canonicalFile
        var n = 0
        for (dex in dexes) for (c in dex.classes) {
            if (isCancelled()) throw InterruptedException("export cancelled")
            val shown = mapping?.classFor(c.type) ?: c.type
            val rel = DexTools.descriptorToJava(shown).replace('.', '/') + ".smali"
            val target = java.io.File(root, rel).canonicalFile
            // Hostile mappings/descriptors ("../") must not escape the output folder.
            if (!target.path.startsWith(root.path + java.io.File.separator)) continue
            target.parentFile?.mkdirs()
            val smali = DexTools.classSmali(c)
            target.writeText(mapping?.deobfuscateSmali(smali) ?: smali)
            n++
            if (n % 200 == 0) onProgress(n)
        }
        onProgress(n)
        return n
    }
}
