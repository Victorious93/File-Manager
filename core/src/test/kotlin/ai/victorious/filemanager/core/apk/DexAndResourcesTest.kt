package ai.victorious.filemanager.core.apk

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DexAndResourcesTest {
    private fun method(cls: String, name: String, params: List<String>) = ImmutableMethod(
        cls, name, params.map { ImmutableMethodParameter(it, null, null) }, "V",
        AccessFlags.PUBLIC.value, null, null,
        ImmutableMethodImplementation(params.size + 1, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), null, null),
    )

    private fun dex(vararg classes: String): ByteArray {
        val pool = DexPool(Opcodes.getDefault())
        classes.forEach { c ->
            pool.internClass(ImmutableClassDef(c, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
                listOf(method(c, "<init>", emptyList()), method(c, "onCreate", listOf("Landroid/os/Bundle;")))))
        }
        val store = MemoryDataStore()
        pool.writeTo(store)
        return store.data.copyOf(store.size)
    }

    private fun tmp() = Files.createTempDirectory("p4").toFile()

    private fun zip(out: File, entries: Map<String, ByteArray>) = ZipOutputStream(out.outputStream()).use { z ->
        entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
    }

    @Test fun dexNamesAndOrder() {
        assertTrue(DexTools.isDexName("classes.dex"))
        assertTrue(DexTools.isDexName("classes12.dex"))
        assertFalse(DexTools.isDexName("assets/classes.dex"))
        assertFalse(DexTools.isDexName("classesX.dex"))
        assertEquals(listOf("classes.dex", "classes2.dex", "classes10.dex"),
            listOf("classes10.dex", "classes2.dex", "classes.dex").sortedBy(DexTools::dexOrder))
    }

    @Test fun listDexInApkFolderAndFile() {
        val d = tmp()
        val bytes = dex("Lcom/example/Main;")
        val apk = File(d, "a.apk")
        zip(apk, mapOf("classes2.dex" to bytes, "classes.dex" to bytes, "assets/classes.dex" to bytes, "AndroidManifest.xml" to ByteArray(4)))
        assertEquals(listOf("classes.dex", "classes2.dex"), DexTools.listDex(apk).map { it.displayName })
        val folder = File(d, "x").apply { mkdirs() }
        File(folder, "classes3.dex").writeBytes(bytes); File(folder, "classes.dex").writeBytes(bytes)
        assertEquals(listOf("classes.dex", "classes3.dex"), DexTools.listDex(folder).map { it.displayName })
        val single = File(d, "one.dex").apply { writeBytes(bytes) }
        assertEquals(1, DexTools.listDex(single).size)
        assertEquals(bytes.size, DexTools.listDex(apk).first().readBytes().size)
    }

    @Test fun classesMethodsAndSmali() {
        val dexFile = DexTools.load(dex("Lcom/example/B;", "Lcom/example/A\$Inner;"))
        val classes = DexTools.classes(dexFile)
        assertEquals(listOf("com.example.A\$Inner", "com.example.B"), classes.map { it.javaName })
        val cls = DexTools.findClass(dexFile, "Lcom/example/B;")
        val methods = DexTools.methods(cls)
        assertTrue(methods.any { it.signature == "onCreate(Landroid/os/Bundle;)V" && it.hasCode })
        val smali = DexTools.classSmali(cls)
        assertTrue(smali, smali.startsWith(".class public Lcom/example/B;"))
        assertTrue(smali.contains(".super Ljava/lang/Object;"))
        val m = SmaliText.method(smali, "onCreate(Landroid/os/Bundle;)V")
        assertNotNull(m)
        assertTrue(m!!.trimStart().startsWith(".method public onCreate(Landroid/os/Bundle;)V"))
        assertTrue(m.contains("return-void"))
        assertTrue(m.trimEnd().endsWith(".end method"))
        assertNull(SmaliText.method(smali, "nope()V"))
        assertEquals(2, SmaliText.methods(smali).size)
    }

    @Test fun descriptorHelpers() {
        assertEquals("a.b.C", DexTools.descriptorToJava("La/b/C;"))
        assertEquals("I", DexTools.descriptorToJava("I"))
        assertEquals(File("/o/smali/a/b/C\$D.smali"), DexTools.smaliExportFile(File("/o"), "La/b/C\$D;"))
    }

    @Test fun axmlDecodesToReadableXml() {
        val bytes = AxmlTestEncoder.manifest("com.example.app", 42, "1.2.3", 21, 34, listOf("android.permission.INTERNET"))
        val xml = AxmlDecoder.toXml(bytes)
        assertTrue(xml, xml.contains("<manifest"))
        assertTrue(xml, xml.contains("xmlns:android=\"http://schemas.android.com/apk/res/android\""))
        assertTrue(xml, xml.contains("android:versionCode=\"42\""))
        assertTrue(xml, xml.contains("package=\"com.example.app\""))
        assertTrue(xml, xml.contains("android:name=\"android.permission.INTERNET\""))
        assertTrue(xml, xml.trimEnd().endsWith("</manifest>"))
        assertFalse(AxmlDecoder.isBinaryXml("<?xml".toByteArray()))
    }

    @Test fun valueFormatting() {
        assertEquals("@0x7f010001", AxmlDecoder.formatValue(null, 0x01, 0x7f010001))
        assertEquals("@null", AxmlDecoder.formatValue(null, 0x01, 0))
        assertEquals("true", AxmlDecoder.formatValue(null, 0x12, -1))
        assertEquals("#ff00ff00", AxmlDecoder.formatValue(null, 0x1c, 0xff00ff00.toInt()))
        assertEquals("16dp", AxmlDecoder.formatValue(null, 0x05, (16 shl 8) or 1))
        assertEquals("raw", AxmlDecoder.formatValue("raw", 0x10, 5))
    }

    @Test fun resourceListing() {
        val d = tmp()
        val manifest = AxmlTestEncoder.manifest("p.q", 1, "1", 21, 34, emptyList())
        val apk = File(d, "r.apk")
        zip(apk, mapOf("AndroidManifest.xml" to manifest, "res/layout/main.xml" to manifest, "res/drawable/icon.png" to ByteArray(3),
            "assets/data.bin" to ByteArray(2), "classes.dex" to ByteArray(1)))
        val entries = ApkResources.list(apk)
        assertEquals(listOf("assets/data.bin", "res/drawable/icon.png", "res/layout/main.xml"), entries.map { it.path })
        assertEquals(listOf(ResKind.OTHER, ResKind.IMAGE, ResKind.XML), entries.map { it.kind })
        assertEquals(listOf("assets", "res/drawable", "res/layout"), ApkResources.folders(entries))
        assertTrue(ApkResources.manifestXml(apk).contains("package=\"p.q\""))
        assertEquals("<a/>", ApkResources.readableXml("<a/>".toByteArray()))
        val folder = File(d, "ex").apply { mkdirs() }
        File(folder, "res/xml").mkdirs(); File(folder, "res/xml/c.xml").writeBytes(manifest)
        File(folder, "AndroidManifest.xml").writeBytes(manifest)
        assertEquals(listOf("res/xml/c.xml"), ApkResources.list(folder).map { it.path })
        assertTrue(ApkResources.manifestXml(folder).contains("<manifest"))
    }
}
