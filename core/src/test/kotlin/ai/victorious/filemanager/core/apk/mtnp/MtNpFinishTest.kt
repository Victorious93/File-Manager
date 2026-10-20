package ai.victorious.filemanager.core.apk.mtnp

import ai.victorious.filemanager.core.apk.Arsc
import ai.victorious.filemanager.core.apk.ArscEditor
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MtNpFinishTest {
    private fun writeDex(vararg classes: Pair<String, List<String>>): ByteArray {
        val pool = DexPool(Opcodes.getDefault())
        for ((cls, strings) in classes) {
            val instrs = buildList {
                strings.forEachIndexed { i, s ->
                    add(ImmutableInstruction21c(Opcode.CONST_STRING, i + 1, ImmutableStringReference(s)))
                }
                add(ImmutableInstruction10x(Opcode.RETURN_VOID))
            }
            val m = ImmutableMethod(
                cls, "run", emptyList(), "V", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
                ImmutableMethodImplementation(strings.size + 1, instrs, null, null),
            )
            pool.internClass(
                ImmutableClassDef(cls, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(m)),
            )
        }
        val store = MemoryDataStore()
        pool.writeTo(store)
        return store.getData()
    }

    @Test fun repairFixesChecksumSignatureAndSize() {
        val good = writeDex("Lcom/t/A;" to listOf("hello"))
        val before = DexRepair.inspect(good)
        assertTrue(before.healthy)
        val broken = good.copyOf()
        ByteBuffer.wrap(broken).order(ByteOrder.LITTLE_ENDIAN).putInt(8, 0).putInt(32, broken.size + 99)
        System.arraycopy(ByteArray(20), 0, broken, 12, 20)
        val brokenReport = DexRepair.inspect(broken)
        assertFalse(brokenReport.checksumOk)
        assertFalse(brokenReport.signatureOk)
        assertNotEquals(broken.size, brokenReport.declaredFileSize)
        val (fixed, report) = DexRepair.repair(broken)
        assertTrue(report.checksumOk)
        assertTrue(report.signatureOk)
        assertEquals(fixed.size, report.declaredFileSize)
        assertTrue(report.fixesApplied.isNotEmpty())
        assertTrue(DexRepair.inspect(fixed).healthy)
    }

    @Test fun repairRejectsBadMagic() {
        try {
            DexRepair.repair(ByteArray(0x80) { 0 })
            org.junit.Assert.fail("expected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("magic"))
        }
    }

    @Test fun mergeAndSplit() {
        val a = writeDex("Lcom/t/A;" to listOf("a"))
        val b = writeDex("Lcom/t/B;" to listOf("b"), "Lcom/t/A;" to listOf("dup"))
        val merged = DexMergeSplit.merge(listOf(a, b))
        assertEquals(2, merged.classCount)
        assertEquals(listOf("Lcom/t/A;"), merged.skippedDuplicates)
        assertTrue(DexRepair.inspect(merged.dex).healthy)
        assertEquals(listOf("Lcom/t/A;", "Lcom/t/B;"), DexMergeSplit.classList(merged.dex))

        val parts = DexMergeSplit.splitByClassCount(merged.dex, classesPerDex = 1)
        assertEquals(2, parts.size)
        assertEquals("classes.dex", parts[0].name)
        assertEquals("classes2.dex", parts[1].name)
        assertEquals(1, parts[0].classCount)
        val back = DexMergeSplit.merge(parts.map { it.dex })
        assertEquals(2, back.classCount)
    }

    @Test fun stringDecryptBase64AndXor() {
        val plain = "Hello Fleet"
        val b64 = java.util.Base64.getEncoder().encodeToString(plain.toByteArray())
        val cands = StringDecrypt.candidates(b64)
        assertTrue(cands.any { it.method == DecryptMethod.BASE64 && it.preview == plain })
        val xored = StringDecrypt.xor(plain.toByteArray(), byteArrayOf(0x42))
        val enc = String(xored, Charsets.ISO_8859_1)
        val applied = StringDecrypt.apply(enc, DecryptMethod.XOR_SINGLE_KEY, "42")
        assertEquals(plain, applied)
        assertEquals(plain, StringDecrypt.apply(b64, DecryptMethod.BASE64))
        val hex = plain.toByteArray().joinToString("") { "%02x".format(it) }
        assertEquals(plain, StringDecrypt.apply(hex, DecryptMethod.HEX_UTF8))
    }

    @Test fun stringDecryptScanDex() {
        val secret = java.util.Base64.getEncoder().encodeToString("secret-value-99".toByteArray())
        val dex = writeDex("Lcom/t/S;" to listOf(secret, "plain"))
        val strings = DexStringEdit.listStrings(dex).map { it.value }
        val hits = StringDecrypt.scanDexStrings(strings)
        assertTrue(hits.any { it.first == secret && it.second.any { c -> c.preview.contains("secret") } })
    }

    @Test fun dexStringReplace() {
        val dex = writeDex("Lcom/t/E;" to listOf("old-text", "keep"))
        val out = DexStringEdit.replaceOne(dex, "old-text", "new-text")
        val values = DexStringEdit.listStrings(out).map { it.value }.toSet()
        assertTrue(values.contains("new-text"))
        assertFalse(values.contains("old-text"))
        assertTrue(values.contains("keep"))
        assertTrue(DexRepair.inspect(out).healthy)
    }

    @Test fun arscTranslateCsvRoundTrip() {
        val fixture = File("src/test/resources/apk/fixture.apk")
        if (!fixture.exists()) {
            // Path when running from module dir vs repo root
            val alt = File("../src/test/resources/apk/fixture.apk")
            org.junit.Assume.assumeTrue("fixture.apk required", fixture.exists() || alt.exists())
        }
        val apk = if (fixture.exists()) fixture else File("../src/test/resources/apk/fixture.apk")
        val arscBytes = ZipFile(apk).use { z -> z.getInputStream(z.getEntry("resources.arsc")).readBytes() }
        val arsc = Arsc.open(arscBytes)
        val locs = arsc.locales()
        assertTrue(locs.contains("default"))
        assertTrue(locs.size >= 2)
        val csv = ArscTranslate.exportCsv(arsc, locs)
        assertTrue(csv.startsWith("resId,name,"))
        val xml = ArscTranslate.exportXml(arsc, "default")
        assertTrue(xml.contains("<resources>"))
        val side = ArscTranslate.sideBySide(arsc)
        assertTrue(side.isNotEmpty())
        val imp = ArscTranslate.importCsv(arsc, csv)
        // Re-importing identical values should produce edits that apply cleanly
        val applied = ArscTranslate.apply(arsc, imp.edits.filterValues { it.isNotEmpty() }.let { edits ->
            // Only keep edits that actually change something to exercise apply; empty filter OK
            edits.filter { (t, v) -> arsc.strings.any { s -> s.resId == t.resId && s.locale == t.locale && s.value != v } }
                .ifEmpty { edits.entries.take(1).associate { it.key to it.value } }
        })
        assertNotNull(Arsc.open(applied))
        val suggestions = ArscTranslate.parseAiResponse(
            arsc, locs.first { it != "default" },
            "app_name\tNombre",
        )
        // May be empty if fixture has no app_name — still must not throw
        assertNotNull(suggestions)
        val prompt = ArscTranslate.aiPrompt(side, "default", locs.first { it != "default" })
        assertTrue(prompt.contains("Translate"))
    }

    @Test fun arscImportXmlUnknownLocaleSkipped() {
        val fixture = File("src/test/resources/apk/fixture.apk")
        org.junit.Assume.assumeTrue(fixture.exists())
        val arscBytes = ZipFile(fixture).use { z -> z.getInputStream(z.getEntry("resources.arsc")).readBytes() }
        val arsc = Arsc.open(arscBytes)
        val r = ArscTranslate.importXml(arsc, "zz", "<resources><string name=\"x\">y</string></resources>")
        assertTrue(r.edits.isEmpty())
        assertTrue(r.skipped.any { it.contains("zz") })
    }
}
