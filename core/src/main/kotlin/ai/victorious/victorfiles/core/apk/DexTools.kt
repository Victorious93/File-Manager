package ai.victorious.victorfiles.core.apk

import com.android.tools.smali.baksmali.Adaptors.ClassDefinition
import com.android.tools.smali.baksmali.BaksmaliOptions
import com.android.tools.smali.baksmali.formatter.BaksmaliWriter
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import java.io.File
import java.io.StringWriter
import java.util.zip.ZipFile

/** Where a DEX lives: a zip entry inside an APK, or a plain file on disk. */
data class DexSource(val container: File, val entryName: String?) {
    val displayName: String get() = entryName ?: container.name
    fun readBytes(): ByteArray =
        if (entryName == null) container.readBytes()
        else ZipFile(container).use { z -> z.getInputStream(z.getEntry(entryName) ?: error("Missing $entryName")).use { it.readBytes() } }
}

data class DexClassInfo(val descriptor: String, val javaName: String, val methodCount: Int, val fieldCount: Int)
data class DexMethodInfo(val name: String, val descriptor: String, val accessFlags: Int, val hasCode: Boolean) {
    /** e.g. `onCreate(Landroid/os/Bundle;)V` — matches the `.method` line tail in smali. */
    val signature: String get() = name + descriptor
}

/**
 * DEX discovery and smali disassembly (read-only) via dexlib2 + baksmali
 * (BSD-3-Clause, maintained by Google under com.android.tools.smali).
 */
object DexTools {
    private val DEX_NAME = Regex("""^classes(\d*)\.dex$""")

    fun isDexName(name: String) = DEX_NAME.matches(name)

    /** Orders classes.dex, classes2.dex, … classes10.dex numerically. */
    fun dexOrder(name: String): Int = DEX_NAME.matchEntire(name)?.groupValues?.get(1)?.let { if (it.isEmpty()) 1 else it.toInt() } ?: Int.MAX_VALUE

    /** Lists top-level classesN.dex in an APK, an extracted folder, or a single .dex file. */
    fun listDex(target: File): List<DexSource> = when {
        target.isDirectory -> (target.listFiles() ?: emptyArray())
            .filter { it.isFile && isDexName(it.name) }
            .sortedBy { dexOrder(it.name) }
            .map { DexSource(it, null) }
        target.extension.equals("dex", true) -> listOf(DexSource(target, null))
        else -> ZipFile(target).use { z ->
            z.entries().asSequence().map { it.name }.filter(::isDexName).sortedBy(::dexOrder).map { DexSource(target, it) }.toList()
        }
    }

    fun load(bytes: ByteArray): DexBackedDexFile = DexBackedDexFile(Opcodes.getDefault(), bytes)
    fun load(source: DexSource): DexBackedDexFile = load(source.readBytes())

    fun descriptorToJava(desc: String): String =
        if (desc.startsWith("L") && desc.endsWith(";")) desc.substring(1, desc.length - 1).replace('/', '.') else desc

    fun classes(dex: DexBackedDexFile): List<DexClassInfo> = dex.classes
        .map { c -> DexClassInfo(c.type, descriptorToJava(c.type), c.methods.count(), c.fields.count()) }
        .sortedBy { it.javaName }

    fun findClass(dex: DexBackedDexFile, descriptor: String): ClassDef =
        dex.classes.firstOrNull { it.type == descriptor } ?: throw IllegalArgumentException("Class not found: $descriptor")

    fun methodDescriptor(m: Method): String =
        m.parameterTypes.joinToString("", "(", ")") + m.returnType

    fun methods(cls: ClassDef): List<DexMethodInfo> =
        cls.methods.map { DexMethodInfo(it.name, methodDescriptor(it), it.accessFlags, it.implementation != null) }

    fun classSmali(cls: ClassDef, apiLevel: Int = 0): String {
        val opts = BaksmaliOptions().apply {
            if (apiLevel > 0) this.apiLevel = apiLevel
            debugInfo = true
            accessorComments = false
        }
        val sw = StringWriter()
        BaksmaliWriter(sw).use { ClassDefinition(opts, cls).writeTo(it) }
        return sw.toString()
    }

    fun classSmali(dex: DexBackedDexFile, descriptor: String): String = classSmali(findClass(dex, descriptor))

    /** Suggested export path: `<dir>/smali/com/example/Foo.smali`. */
    fun smaliExportFile(dir: File, descriptor: String): File =
        File(dir, "smali/" + descriptor.removePrefix("L").removeSuffix(";") + ".smali")
}

/** Pure text helpers over baksmali output. */
object SmaliText {
    data class MethodBlock(val header: String, val signature: String, val text: String)

    /** Splits class smali into `.method … .end method` blocks. */
    fun methods(classSmali: String): List<MethodBlock> {
        val out = mutableListOf<MethodBlock>()
        val lines = classSmali.lines()
        var i = 0
        while (i < lines.size) {
            val l = lines[i].trimStart()
            if (l.startsWith(".method ")) {
                val start = i
                while (i < lines.size && lines[i].trim() != ".end method") i++
                val text = lines.subList(start, minOf(i + 1, lines.size)).joinToString("\n")
                out += MethodBlock(l, l.substringAfterLast(' '), text)
            }
            i++
        }
        return out
    }

    /** Returns the smali of one method by `name(params)ret` signature, or null. */
    fun method(classSmali: String, signature: String): String? = methods(classSmali).firstOrNull { it.signature == signature }?.text
}
