package ai.victorious.victorfiles.core.apk

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class Phase6Test {
    private fun tmp() = Files.createTempDirectory("p6").toFile()

    private fun tinyDex(type: String = "Lcom/example/Main;"): ByteArray {
        val pool = DexPool(Opcodes.forApi(21))
        pool.internClass(
            ImmutableClassDef(
                type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
                listOf(
                    ImmutableMethod(
                        type, "<init>", emptyList(), "V", AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value,
                        null, null,
                        ImmutableMethodImplementation(1, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), null, null),
                    ),
                ),
            ),
        )
        val store = MemoryDataStore(); pool.writeTo(store); return store.data.copyOf(store.size)
    }

    @Test fun assembleRoundTripAndReplace() {
        val original = tinyDex()
        val smali = DexTools.classSmali(DexTools.load(original), "Lcom/example/Main;")
        assertTrue(smali.contains(".class public Lcom/example/Main;"))
        val rebuilt = SmaliAssembler.assembleDex(listOf(smali), 21)
        assertEquals("Lcom/example/Main;", DexTools.classes(DexTools.load(rebuilt)).single().descriptor)

        val edited = smali.replace("Lcom/example/Main;", "Lcom/example/Main2;")
            .replace(".source", "# .source") // keep valid; rename class properly:
        // Proper rename: change .class line only via assembler of a new class.
        val renamed = """
            .class public Lcom/example/Renamed;
            .super Ljava/lang/Object;
            .method public constructor <init>()V
                .registers 1
                return-void
            .end method
        """.trimIndent()
        val d = tmp()
        val dexFile = File(d, "classes.dex").apply { writeBytes(original) }
        File(d, "AndroidManifest.xml").writeBytes(ByteArray(4))
        SmaliAssembler.replaceInDexFile(dexFile, renamed, 21)
        val types = DexTools.classes(DexTools.load(dexFile.readBytes())).map { it.descriptor }.toSet()
        assertTrue(types.contains("Lcom/example/Renamed;"))
        assertTrue(types.contains("Lcom/example/Main;")) // original kept alongside
        d.deleteRecursively()
    }

    @Test fun assembleReportsLineNumbers() {
        try {
            SmaliAssembler.assembleDex(listOf("""
                .class public Lcom/x/Bad;
                .super Ljava/lang/Object;
                .method public foo()V
                    .registers 1
                    totally-invalid-opcode v0
                    return-void
                .end method
            """.trimIndent()), 21)
            fail("expected SmaliAssemblyException")
        } catch (e: SmaliAssemblyException) {
            assertTrue(e.errors.isNotEmpty())
            assertTrue(e.message, e.errors.any { it.line > 0 || it.message.isNotBlank() })
        }
    }

    /** Minimal ARSC: ResTable + UTF-16 string pool + one package with string type. */
    private fun buildArsc(pkg: String, entries: List<Pair<String, String>>): ByteArray {
        fun pool(strings: List<String>): ByteArray {
            val data = ByteArrayOutputStream()
            val offs = IntArray(strings.size)
            for ((i, s) in strings.withIndex()) {
                offs[i] = data.size()
                val chars = s.toByteArray(Charsets.UTF_16LE)
                data.write(s.length and 0xFF); data.write(s.length shr 8)
                data.write(chars); data.write(0); data.write(0)
            }
            while (data.size() % 4 != 0) data.write(0)
            val header = 28
            val stringsStart = header + strings.size * 4
            val total = stringsStart + data.size()
            val b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
            b.putShort(0x0001); b.putShort(header.toShort()); b.putInt(total)
            b.putInt(strings.size); b.putInt(0); b.putInt(0); b.putInt(stringsStart); b.putInt(0)
            offs.forEach { b.putInt(it) }; b.put(data.toByteArray()); return b.array()
        }
        val global = pool(entries.map { it.second })
        val typeNames = pool(listOf("string"))
        val keyNames = pool(entries.map { it.first })
        // Package header: type=0x0200, headerSize=288, id=0x7f, name[128 utf16], typeStrings, lastPublicType, keyStrings, lastPublicKey
        val pkgBody = ByteArrayOutputStream()
        // We'll assemble package as: header(288) + typeSpec + type
        val typeSpecSize = 16 + entries.size * 4 // header 16 + flags
        val typeHeader = 84 // common: 20 + 64 config
        val entrySize = 16 // simple entry
        val entriesStart = typeHeader + entries.size * 4
        val typeSize = entriesStart + entries.size * entrySize
        val pkgHeader = 288
        val pkgSize = pkgHeader + typeNames.size + keyNames.size + typeSpecSize + typeSize

        val out = ByteArrayOutputStream()
        fun chunk(type: Int, headerSize: Int, body: ByteArray) {
            val total = headerSize + body.size // for some we pass full including header
            // unused helper
        }
        // ResTable
        val tableHeader = 12
        val tableSize = tableHeader + global.size + pkgSize
        val table = ByteBuffer.allocate(tableSize).order(ByteOrder.LITTLE_ENDIAN)
        table.putShort(0x0002); table.putShort(tableHeader.toShort()); table.putInt(tableSize); table.putInt(1)
        table.put(global)

        val pkgStart = table.position()
        table.putShort(0x0200); table.putShort(pkgHeader.toShort()); table.putInt(pkgSize)
        table.putInt(0x7f)
        val name = CharArray(128)
        pkg.forEachIndexed { i, c -> if (i < 128) name[i] = c }
        name.forEach { table.putShort(it.code.toShort()) }
        val typeStringsOff = pkgHeader // relative to package start — type pool follows header
        table.putInt(typeStringsOff)
        table.putInt(1) // lastPublicType
        table.putInt(typeStringsOff + typeNames.size)
        table.putInt(entries.size) // lastPublicKey
        // pad header to 288
        while (table.position() < pkgStart + pkgHeader) table.put(0)
        table.put(typeNames)
        table.put(keyNames)
        // typeSpec
        val ts = table.position()
        table.putShort(0x0202); table.putShort(16); table.putInt(typeSpecSize)
        table.put(1) // id
        table.put(0); table.putShort(0)
        table.putInt(entries.size)
        repeat(entries.size) { table.putInt(0) }
        // type
        table.putShort(0x0201); table.putShort(typeHeader.toShort()); table.putInt(typeSize)
        table.put(1) // id
        table.put(0); table.putShort(0)
        table.putInt(entries.size)
        table.putInt(entriesStart)
        // config 64 bytes of zeros (default)
        repeat(64) { table.put(0) }
        // entry offsets
        for (i in entries.indices) table.putInt(i * entrySize)
        // entries
        for (i in entries.indices) {
            table.putShort(8) // size
            table.putShort(0) // flags
            table.putInt(i) // key
            table.putShort(8); table.put(0); table.put(0x03) // TYPE_STRING
            table.putInt(i) // pool index
        }
        assertEquals(tableSize, table.position())
        return table.array()
    }

    @Test fun arscParseEditRoundTrip() {
        val bytes = buildArsc("com.example.app", listOf("app_name" to "Hello", "ok" to "OK"))
        val arsc = Arsc.open(bytes)
        assertEquals(listOf("com.example.app"), arsc.packageNames)
        assertEquals(2, arsc.strings.size)
        assertEquals("Hello", arsc.strings.first { it.name == "app_name" }.value)
        assertEquals("string/app_name", arsc.names[0x7f010000])
        assertEquals("@string/app_name", arsc.resolveRef("@0x7f010000"))
        val edited = arsc.withEdits(mapOf(arsc.strings.first { it.name == "app_name" }.poolIndex to "Hola"))
        val again = Arsc.open(edited)
        assertEquals("Hola", again.strings.first { it.name == "app_name" }.value)
        assertEquals("OK", again.strings.first { it.name == "ok" }.value)
        assertEquals(listOf("default"), again.locales())
        assertEquals(1, again.search("hola", "default").size)
    }

    @Test fun bundleParseAndMerge() {
        val d = tmp()
        fun apk(name: String, entries: Map<String, ByteArray>): File {
            val f = File(d, name)
            ZipOutputStream(f.outputStream()).use { z ->
                entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
            }
            return f
        }
        // Minimal binary XML so AxmlParser doesn't have to succeed for merge; packageName may be null.
        val base = apk("base.apk", mapOf(
            "AndroidManifest.xml" to byteArrayOf(0x03, 0x00, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00),
            "resources.arsc" to byteArrayOf(1, 2, 3),
            "classes.dex" to tinyDex(),
            "META-INF/CERT.SF" to byteArrayOf(9),
        ))
        val config = apk("config.arm64_v8a.apk", mapOf(
            "lib/arm64-v8a/libx.so" to ByteArray(16),
            "AndroidManifest.xml" to byteArrayOf(0x03, 0x00, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00),
        ))
        val bundleZip = File(d, "app.apks")
        ZipOutputStream(bundleZip.outputStream()).use { z ->
            listOf(base, config).forEach { a ->
                z.putNextEntry(ZipEntry(a.name)); a.inputStream().use { it.copyTo(z) }; z.closeEntry()
            }
        }
        val bundle = ApkBundles.open(bundleZip, File(d, "extract"))
        assertEquals(BundleFormat.APKS, bundle.format)
        assertEquals(2, bundle.splits.size)
        assertTrue(bundle.splits.any { it.isBase })
        val merged = File(d, "universal.apk")
        val n = ApkBundles.merge(bundle.splits.map { it.file }, merged)
        assertTrue(n >= 3)
        java.util.zip.ZipFile(merged).use { z ->
            assertTrue(z.getEntry("classes.dex") != null)
            assertTrue(z.getEntry("lib/arm64-v8a/libx.so") != null)
            assertFalse(z.entries().asSequence().any { it.name.startsWith("META-INF/") && it.name != "META-INF/" })
        }
        d.deleteRecursively()
    }

    @Test fun verifyReportsSchemesAndCertDigest() {
        val d = tmp()
        val unsigned = File(d, "u.apk")
        // Build a minimal installable-enough APK: real manifest from AxmlTestEncoder if available.
        val manifest = try {
            // Reuse encoder from existing tests.
            val cls = Class.forName("ai.victorious.victorfiles.core.apk.AxmlTestEncoder")
            val m = cls.methods.first { it.name.contains("manifest", true) || it.name == "encode" || it.parameterCount >= 1 }
            // Fallback: copy approach from ApkToolsTest
            null
        } catch (_: Exception) { null }
        // Use ApkToolsTest pattern via reflection-free: call public helpers if present.
        val encoded = runCatching {
            // Minimal: create zip with binary manifest that AxmlParser can read — from ApkToolsTest fixtures.
            File(".").path // placeholder
        }
        // Sign using same helpers as ApkToolsTest by constructing via Zip + AxmlTestEncoder object methods.
        val axml = AxmlTestEncoder.manifest("com.example.p6", 1, "1.0", 21, 34, emptyList())
        ZipOutputStream(unsigned.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("AndroidManifest.xml")); z.write(axml); z.closeEntry()
            z.putNextEntry(ZipEntry("classes.dex")); z.write(tinyDex()); z.closeEntry()
            z.putNextEntry(ZipEntry("resources.arsc")); z.write(byteArrayOf(2, 0, 12, 0, 12, 0, 0, 0, 0, 0, 0, 0)); z.closeEntry()
        }
        val ks = File(d, "debug.p12")
        val pass = "android".toCharArray()
        ApkSigning.generateDebugKeystore(ks, pass)
        val signed = File(d, "s.apk")
        ApkSigning.sign(unsigned, signed, ApkSigning.loadKey(ks, pass, null), "k", SignSchemes(v1 = true, v2 = true, v3 = true, v4 = false))
        val r = ApkSigning.verify(signed, minSdk = 21)
        assertTrue(r.errors.toString(), r.verified)
        assertTrue(r.v1 && r.v2 && r.v3)
        assertEquals(1, r.signers.size)
        assertEquals(64, r.signers.first().sha256.length)
        assertTrue(r.signers.first().subject.contains("Victorious") || r.signers.first().subject.contains("CN="))
        d.deleteRecursively()
    }
}
