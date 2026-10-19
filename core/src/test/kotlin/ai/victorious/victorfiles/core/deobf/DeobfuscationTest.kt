package ai.victorious.victorfiles.core.deobf

import ai.victorious.victorfiles.core.apk.DexTools
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeobfuscationTest {
    private val mappingText = """
        # compiler: R8
        com.example.secret.Crypto -> a.a:
            java.lang.String KEY -> a
            1:4:java.lang.String decrypt(java.lang.String) -> a
            byte[] decrypt(byte[]) -> a
            5:9:void init(com.example.secret.Config,int) -> b
        com.example.secret.Config -> a.b:
            int level -> c
    """.trimIndent()

    @Test fun parsesMappingAndRewritesSmali() {
        val m = ProguardMapping.parse(mappingText)
        assertEquals(2, m.classCount)
        assertEquals("Lcom/example/secret/Crypto;", m.classFor("La/a;"))
        assertEquals("decrypt", m.methodFor("La/a;", "a", "Ljava/lang/String;"))
        assertEquals("decrypt", m.methodFor("La/a;", "a", "[B"))
        // params use *obfuscated* types in smali
        assertEquals("init", m.methodFor("La/a;", "b", "La/b;I"))
        assertEquals("KEY", m.fieldFor("La/a;", "a"))

        val smali = """
            .class public La/a;
            .super Ljava/lang/Object;
            .field private static a:Ljava/lang/String;
            .method public static a(Ljava/lang/String;)Ljava/lang/String;
                sget-object v0, La/a;->a:Ljava/lang/String;
                iget v1, p0, La/b;->c:I
                invoke-static {p0}, La/a;->a(Ljava/lang/String;)Ljava/lang/String;
                invoke-virtual {p0, v1}, La/a;->b(La/b;I)V
                return-object v0
            .end method
        """.trimIndent()
        val out = m.deobfuscateSmali(smali)
        assertTrue(out, out.contains(".class public Lcom/example/secret/Crypto;"))
        assertTrue(out, out.contains(".field private static KEY:Ljava/lang/String;"))
        assertTrue(out, out.contains(".method public static decrypt(Ljava/lang/String;)Ljava/lang/String;"))
        assertTrue(out, out.contains("Lcom/example/secret/Crypto;->KEY:Ljava/lang/String;"))
        assertTrue(out, out.contains("Lcom/example/secret/Config;->level:I"))
        assertTrue(out, out.contains("Lcom/example/secret/Crypto;->init(Lcom/example/secret/Config;I)V"))
        assertFalse(out, out.contains("La/a;"))
    }

    @Test fun javaTypesToDescriptors() {
        assertEquals("[[I", ProguardMapping.javaToDescriptor("int[][]"))
        assertEquals("Ljava/lang/String;", ProguardMapping.javaToDescriptor("java.lang.String"))
        assertEquals("V", ProguardMapping.javaToDescriptor("void"))
    }

    private fun obfDex(): ByteArray {
        val pool = DexPool(Opcodes.getDefault())
        val dec = ImmutableMethodReference("La/a;", "a", listOf("Ljava/lang/String;"), "Ljava/lang/String;")
        pool.internClass(ImmutableClassDef("La/a;", AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
            listOf(ImmutableMethod("La/a;", "a", listOf(ImmutableMethodParameter("Ljava/lang/String;", null, null)), "Ljava/lang/String;",
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
                ImmutableMethodImplementation(1, listOf(ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)), null, null)))))
        val calls = (1..4).flatMap { i ->
            listOf(
                ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("aGVsbG8gd29ybGQ${i}xyz")),
                ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 0, 0, 0, 0, 0, dec),
            )
        } + ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("api_endpoint_v2")) +
            ImmutableInstruction10x(Opcode.RETURN_VOID)
        pool.internClass(ImmutableClassDef("La/c;", AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
            listOf(ImmutableMethod("La/c;", "run", null, "V", AccessFlags.PUBLIC.value, null, null,
                ImmutableMethodImplementation(2, calls, null, null)))))
        val store = MemoryDataStore(); pool.writeTo(store); return store.data.copyOf(store.size)
    }

    @Test fun dexSearchFindsClassesMethodsAndStringsWithMapping() {
        val dex = DexTools.load(obfDex())
        val m = ProguardMapping.parse(mappingText)
        val byOriginal = DexSearch.search(listOf("classes.dex" to dex), "Crypto", setOf(DexHitKind.CLASS), m)
        assertEquals(listOf("com.example.secret.Crypto"), byOriginal.map { it.detail })
        val methods = DexSearch.search(listOf("classes.dex" to dex), "decrypt", setOf(DexHitKind.METHOD), m)
        assertEquals(1, methods.size)
        val strings = DexSearch.search(listOf("classes.dex" to dex), "endpoint", setOf(DexHitKind.STRING))
        assertEquals(1, strings.size)
        assertTrue(strings[0].detail.contains("api_endpoint_v2"))
    }

    @Test fun decryptionHintsRankStaticStringToStringHelpers() {
        val dex = DexTools.load(obfDex())
        val hints = StringDecryptionHints.find(listOf(dex))
        assertEquals(1, hints.size)
        assertEquals(4, hints[0].callSites)
        assertTrue(hints[0].reason, hints[0].reason.contains("encoded-looking"))
        assertTrue(StringDecryptionHints.looksEncoded("aGVsbG8gd29ybGQ1xyz"))
        assertFalse(StringDecryptionHints.looksEncoded("hello"))
        assertFalse(StringDecryptionHints.looksEncoded("Settings saved"))
    }

    @Test fun smaliExportWritesDeobfuscatedPaths() {
        val dir = kotlin.io.path.createTempDirectory("smali-export").toFile()
        try {
            val dex = DexTools.load(obfDex())
            val m = ProguardMapping.parse(mappingText)
            val n = SmaliExport.export(listOf(dex), dir, m)
            assertTrue(n >= 1)
            val f = java.io.File(dir, "com/example/secret/Crypto.smali")
            assertTrue("exported files: " + dir.walk().toList(), f.isFile)
            assertTrue(f.readText().contains(".class public Lcom/example/secret/Crypto;"))
            // Hostile mapping: original name with ../ must not escape outDir.
            val evil = ProguardMapping.parse("../../evil.Pwn -> a.a:\n")
            val out2 = java.io.File(dir, "evil-out")
            SmaliExport.export(listOf(dex), out2, evil)
            assertFalse(java.io.File(dir.parentFile, "evil").exists())
            assertTrue(out2.walk().all { it.canonicalPath.startsWith(out2.canonicalPath) })
        } finally { dir.deleteRecursively() }
    }
}
