package ai.victorious.filemanager.core.apk.mtnp

import ai.victorious.filemanager.core.apk.Arsc
import ai.victorious.filemanager.core.apk.ArscStringAdder
import ai.victorious.filemanager.core.apk.DexTools
import ai.victorious.filemanager.core.apk.SmaliAssembler
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction12x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21s
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22b
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 16: dex repair report, merge/rebalance + duplicate policy, restricted string-decrypt
 * emulator, ARSC/DEX add-strings. Uses synthetic DEX + fixture.apk resources.arsc.
 */
class Phase16Test {
    private fun fixtureApk(): File {
        val url = javaClass.classLoader.getResource("apk/fixture.apk")
            ?: error("Missing test resource apk/fixture.apk")
        return File(url.toURI())
    }

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
        val typeSpecSize = 16 + entries.size * 4
        val typeHeader = 84
        val entrySize = 16
        val entriesStart = typeHeader + entries.size * 4
        val typeSize = entriesStart + entries.size * entrySize
        val pkgHeader = 288
        val pkgSize = pkgHeader + typeNames.size + keyNames.size + typeSpecSize + typeSize
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
        val typeStringsOff = pkgHeader
        table.putInt(typeStringsOff)
        table.putInt(1)
        table.putInt(typeStringsOff + typeNames.size)
        table.putInt(entries.size)
        while (table.position() < pkgStart + pkgHeader) table.put(0)
        table.put(typeNames)
        table.put(keyNames)
        table.putShort(0x0202); table.putShort(16); table.putInt(typeSpecSize)
        table.put(1); table.put(0); table.putShort(0); table.putInt(entries.size)
        repeat(entries.size) { table.putInt(0) }
        table.putShort(0x0201); table.putShort(typeHeader.toShort()); table.putInt(typeSize)
        table.put(1); table.put(0); table.putShort(0)
        table.putInt(entries.size); table.putInt(entriesStart)
        repeat(64) { table.put(0) }
        for (i in entries.indices) table.putInt(i * entrySize)
        for (i in entries.indices) {
            table.putShort(8); table.putShort(0); table.putInt(i)
            table.putShort(8); table.put(0); table.put(0x03); table.putInt(i)
        }
        assertEquals(tableSize, table.position())
        return table.array()
    }

    @Test fun repairReportsEveryFix() {
        val good = SmaliAssembler.assembleDex(
            listOf(
                """
                .class public Lcom/t/A;
                .super Ljava/lang/Object;
                .method public static run()V
                    .registers 1
                    return-void
                .end method
                """.trimIndent(),
            ),
        )
        val broken = good.copyOf()
        broken[0] = 'D'.code.toByte() // corrupt magic but keep rest looking DEX-like
        ByteBuffer.wrap(broken).order(ByteOrder.LITTLE_ENDIAN).putInt(8, 0).putInt(32, broken.size + 50)
        System.arraycopy(ByteArray(20), 0, broken, 12, 20)
        val (fixed, report) = DexRepair.repair(broken)
        assertTrue(report.fixesApplied.any { it.startsWith("magic") })
        assertTrue(report.fixesApplied.any { it.contains("file_size") })
        assertTrue(report.fixesApplied.any { it.contains("signature") })
        assertTrue(report.fixesApplied.any { it.contains("checksum") })
        assertTrue(DexRepair.inspect(fixed).healthy)
    }

    @Test fun mergeDuplicatePoliciesAndRebalance() {
        val a = SmaliAssembler.assembleDex(
            listOf(
                """
                .class public Lcom/t/A;
                .super Ljava/lang/Object;
                .method public static a()V
                    .registers 1
                    return-void
                .end method
                .method public static a2()V
                    .registers 1
                    return-void
                .end method
                """.trimIndent(),
            ),
        )
        val b = SmaliAssembler.assembleDex(
            listOf(
                """
                .class public Lcom/t/A;
                .super Ljava/lang/Object;
                .method public static onlyInB()V
                    .registers 1
                    return-void
                .end method
                """.trimIndent(),
                """
                .class public Lcom/t/B;
                .super Ljava/lang/Object;
                .method public static b()V
                    .registers 1
                    return-void
                .end method
                """.trimIndent(),
            ),
        )
        val dups = DexMergeSplit.findDuplicates(listOf(a, b))
        assertTrue(dups.containsKey("Lcom/t/A;"))
        val first = DexMergeSplit.merge(listOf(a, b), DexMergeSplit.DuplicatePolicy.FIRST)
        assertEquals(2, first.classCount)
        val methodsFirst = DexMergeSplit.load(first.dex).classes.first { it.type == "Lcom/t/A;" }.methods.map { it.name }.toSet()
        assertTrue(methodsFirst.contains("a2"))
        assertFalse(methodsFirst.contains("onlyInB"))
        val last = DexMergeSplit.merge(listOf(a, b), DexMergeSplit.DuplicatePolicy.LAST)
        val methodsLast = DexMergeSplit.load(last.dex).classes.first { it.type == "Lcom/t/A;" }.methods.map { it.name }.toSet()
        assertTrue(methodsLast.contains("onlyInB"))
        val larger = DexMergeSplit.merge(listOf(a, b), DexMergeSplit.DuplicatePolicy.PREFER_LARGER)
        val methodsLarger = DexMergeSplit.load(larger.dex).classes.first { it.type == "Lcom/t/A;" }.methods.map { it.name }.toSet()
        assertTrue(methodsLarger.contains("a2"))
        val parts = DexMergeSplit.mergeAndRebalance(listOf(a, b), methodBudget = 2)
        assertTrue(parts.size >= 1)
        assertTrue(parts.all { it.methodCount <= 2 || it.classCount == 1 })
    }

    @Test fun stringDecryptEmulatorXorPureMethod() {
        // static String f(String): XOR each char with 0x11
        val decryptor = """
            .class public Lcom/t/Dec;
            .super Ljava/lang/Object;
            .method public static f(Ljava/lang/String;)Ljava/lang/String;
                .registers 5
                invoke-virtual {p0}, Ljava/lang/String;->toCharArray()[C
                move-result-object v0
                array-length v1, v0
                const/4 v2, 0x0
            :loop
                if-ge v2, v1, :done
                aget-char v3, v0, v2
                xor-int/lit8 v3, v3, 0x11
                int-to-char v3, v3
                aput-char v3, v0, v2
                add-int/lit8 v2, v2, 0x1
                goto :loop
            :done
                new-instance v2, Ljava/lang/String;
                invoke-direct {v2, v0}, Ljava/lang/String;-><init>([C)V
                return-object v2
            .end method
        """.trimIndent()
        val caller = """
            .class public Lcom/t/Use;
            .super Ljava/lang/Object;
            .method public static run()Ljava/lang/String;
                .registers 2
                const-string v0, "Ypp`"
                invoke-static {v0}, Lcom/t/Dec;->f(Ljava/lang/String;)Ljava/lang/String;
                move-result-object v0
                return-object v0
            .end method
        """.trimIndent()
        // "Ypp`" XOR 0x11 → "Hello"? Let's compute: H=0x48 xor 0x11=0x59='Y', e=0x65^0x11=0x74='t' — use known plaintext
        val plain = "Hi"
        val enc = plain.map { (it.code xor 0x11).toChar() }.joinToString("")
        val caller2 = """
            .class public Lcom/t/Use;
            .super Ljava/lang/Object;
            .method public static run()Ljava/lang/String;
                .registers 2
                const-string v0, "$enc"
                invoke-static {v0}, Lcom/t/Dec;->f(Ljava/lang/String;)Ljava/lang/String;
                move-result-object v0
                return-object v0
            .end method
        """.trimIndent()
        val dexBytes = SmaliAssembler.assembleDex(listOf(decryptor, caller2))
        val dex = DexTools.load(dexBytes)
        val methods = StringDecryptEmulator.findDecryptors(listOf(dex), minCallSites = 1)
        assertTrue(methods.any { it.name == "f" && it.classType == "Lcom/t/Dec;" })
        val meth = methods.first { it.name == "f" }
        val sites = StringDecryptEmulator.collectConstantCallSites(listOf(dex), meth)
        assertEquals(1, sites.size)
        assertEquals(enc, sites[0].arg)
        val result = StringDecryptEmulator.emulate(listOf(dex), meth, enc)
        assertTrue(result.error ?: "", result.ok)
        assertEquals(plain, result.plaintext)
        val inlined = StringDecryptEmulator.inlineIntoDex(dexBytes, meth, mapOf(enc to plain))
        val smali = DexTools.classSmali(DexTools.load(inlined), "Lcom/t/Use;")
        assertTrue(smali.contains("const-string") && smali.contains(plain))
    }

    @Test fun emulatorRejectsForbiddenApis() {
        val bad = """
            .class public Lcom/t/Bad;
            .super Ljava/lang/Object;
            .method public static f(Ljava/lang/String;)Ljava/lang/String;
                .registers 2
                invoke-static {}, Ljava/lang/Runtime;->getRuntime()Ljava/lang/Runtime;
                move-result-object v0
                return-object p0
            .end method
        """.trimIndent()
        val dexBytes = SmaliAssembler.assembleDex(listOf(bad))
        val dex = DexTools.load(dexBytes)
        val meth = StringDecryptEmulator.DecryptorMethod(
            "Lcom/t/Bad;", "f", "Ljava/lang/String;", "Ljava/lang/String;", 0, 0, "test",
        )
        val r = StringDecryptEmulator.emulate(listOf(dex), meth, "x")
        assertFalse(r.ok)
        assertNotNull(r.error)
        assertTrue(r.error!!.contains("blocked") || r.error!!.contains("not allowed") || r.error!!.contains("unsupported"))
    }

    @Test fun addArscStringsAllConfigs() {
        val bytes = buildArsc("com.example.app", listOf("app_name" to "Hello", "ok" to "OK"))
        val result = ArscStringAdder.addStrings(bytes, mapOf("greet" to "Hi there", "bye" to "Bye"))
        val a = Arsc.open(result.bytes)
        val defaults = a.strings.filter { it.locale == "default" }.associate { it.name to it.value }
        assertEquals("Hello", defaults["app_name"])
        assertEquals("OK", defaults["ok"])
        assertEquals("Hi there", defaults["greet"])
        assertEquals("Bye", defaults["bye"])
        assertEquals(2, result.added.size)
        assertTrue(result.added.all { (it.resId ushr 16) and 0xFF == 1 })
    }

    @Test fun addArscStringsOnFixtureApk() {
        val apk = fixtureApk()
        val arscBytes = ZipFile(apk).use { z -> z.getInputStream(z.getEntry("resources.arsc")).readBytes() }
        val before = Arsc.open(arscBytes)
        val name = "fm_phase16_added"
        require(before.strings.none { it.name == name })
        val result = ArscStringAdder.addStrings(arscBytes, mapOf(name to "Phase16"))
        val after = Arsc.open(result.bytes)
        val hits = after.strings.filter { it.name == name }
        assertTrue(hits.isNotEmpty())
        // Present in every locale that the string type had (at least default).
        val locales = before.locales()
        for (loc in locales) {
            // New entry is added to every type config; value is the same pool string.
            assertTrue("missing in $loc", hits.any { it.locale == loc } || loc == "default" && hits.any { it.locale == "default" })
        }
        assertEquals("Phase16", hits.first().value)
    }

    @Test fun addDexStringConstants() {
        val base = SmaliAssembler.assembleDex(
            listOf(
                """
                .class public Lcom/t/A;
                .super Ljava/lang/Object;
                .method public static run()V
                    .registers 1
                    return-void
                .end method
                """.trimIndent(),
            ),
        )
        val out = DexStringEdit.addConstantsClass(base, listOf("alpha", "beta"))
        val values = DexStringEdit.listStrings(out).map { it.value }.toSet()
        assertTrue(values.contains("alpha"))
        assertTrue(values.contains("beta"))
        assertTrue(DexRepair.inspect(out).healthy)
        assertTrue(DexMergeSplit.classList(out).contains("Lfm/AddedStrings;"))
    }
}
