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
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApkCompareTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun dex(classes: Map<String, List<String>>): ByteArray {
        val pool = DexPool(Opcodes.getDefault())
        classes.forEach { (c, methods) ->
            pool.internClass(ImmutableClassDef(c, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
                methods.map { m ->
                    ImmutableMethod(c, m, null, "V", AccessFlags.PUBLIC.value, null, null,
                        ImmutableMethodImplementation(1, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), null, null))
                }))
        }
        val store = MemoryDataStore(); pool.writeTo(store); return store.data.copyOf(store.size)
    }

    private fun apk(name: String, version: Int, perms: List<String>, classes: Map<String, List<String>>, extra: Map<String, String>): File {
        val f = File(tmp.root, name)
        ZipOutputStream(f.outputStream()).use { z ->
            fun put(n: String, b: ByteArray) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
            put("AndroidManifest.xml", AxmlTestEncoder.manifest("com.example.app", version, "1.$version", 24, 34, perms))
            put("classes.dex", dex(classes))
            extra.forEach { (n, v) -> put(n, v.toByteArray()) }
        }
        return f
    }

    @Test fun diffsManifestPermissionsClassesAndEntries() {
        val a = apk("a.apk", 1, listOf("android.permission.INTERNET", "android.permission.CAMERA"),
            mapOf("Lcom/example/A;" to listOf("run"), "Lcom/example/Old;" to listOf("x"), "Lcom/example/Same;" to listOf("s")),
            mapOf("res/raw/a.txt" to "one", "assets/gone.txt" to "bye"))
        val b = apk("b.apk", 2, listOf("android.permission.INTERNET", "android.permission.RECORD_AUDIO"),
            mapOf("Lcom/example/A;" to listOf("run", "stop"), "Lcom/example/New;" to listOf("y"), "Lcom/example/Same;" to listOf("s")),
            mapOf("res/raw/a.txt" to "two", "assets/new.txt" to "hi"))
        val c = ApkCompare.compare(a, b)
        assertFalse(c.identical)
        assertEquals("1" to "2", c.manifest.first { it.field == "versionCode" }.let { it.left to it.right })
        assertFalse(c.manifest.first { it.field == "package" }.changed)
        assertEquals(listOf("android.permission.RECORD_AUDIO"), c.permissions.added)
        assertEquals(listOf("android.permission.CAMERA"), c.permissions.removed)
        assertEquals(listOf("Lcom/example/New;"), c.classes.added)
        assertEquals(listOf("Lcom/example/Old;"), c.classes.removed)
        assertEquals(1, c.changedClasses.size)
        assertEquals("Lcom/example/A;", c.changedClasses[0].descriptor)
        assertEquals(listOf("stop()V"), c.changedClasses[0].methodsAdded)
        assertEquals(listOf("assets/new.txt"), c.entries.added)
        assertEquals(listOf("assets/gone.txt"), c.entries.removed)
        assertTrue(c.changedEntries.any { it.name == "res/raw/a.txt" })
        assertTrue(c.changedEntries.any { it.name == "classes.dex" })
        val report = ApkCompare.report(c)
        assertTrue(report, report.contains("+ android.permission.RECORD_AUDIO"))
        assertTrue(report, report.contains("~ com.example.A"))
    }

    @Test fun identicalApksReportNoDifferences() {
        val a = apk("x.apk", 3, listOf("android.permission.INTERNET"), mapOf("Lq/Q;" to listOf("q")), mapOf("r.txt" to "r"))
        val b = File(tmp.root, "y.apk").also { a.copyTo(it) }
        val c = ApkCompare.compare(a, b)
        assertTrue(ApkCompare.report(c), c.identical)
    }
}
