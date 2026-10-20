package ai.victorious.filemanager.core.apk

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkToolsTest {
    private val tmp: File = Files.createTempDirectory("apktools").toFile()
    private val manifest = AxmlTestEncoder.manifest(
        "com.example.demo", 42, "1.2.3", 24, 35,
        listOf("android.permission.INTERNET", "android.permission.CAMERA", "android.permission.INTERNET"),
    )

    private fun extractedFolder(m: ByteArray = manifest): File = File(tmp, "demo_extracted").apply {
        mkdirs()
        File(this, "AndroidManifest.xml").writeBytes(m)
        File(this, "resources.arsc").writeBytes(ByteArray(1001) { it.toByte() })
        File(this, "classes.dex").writeBytes("dex\n035\u0000".toByteArray() + ByteArray(500))
        File(this, "lib/arm64-v8a").mkdirs(); File(this, "lib/arm64-v8a/libdemo.so").writeBytes(ByteArray(777) { 7 })
        File(this, "res/raw").mkdirs(); File(this, "res/raw/a.txt").writeText("hello".repeat(100))
        File(this, "META-INF").mkdirs()
        File(this, "META-INF/MANIFEST.MF").writeText("Manifest-Version: 1.0\n")
        File(this, "META-INF/OLD.RSA").writeBytes(ByteArray(10))
        File(this, "META-INF/services").mkdirs(); File(this, "META-INF/services/x").writeText("keep")
    }

    @Test fun parsesUtf16Manifest() {
        val info = AxmlParser.parseManifest(manifest)
        assertEquals("com.example.demo", info.packageName)
        assertEquals(42L, info.versionCode)
        assertEquals("1.2.3", info.versionName)
        assertEquals(24, info.minSdk)
        assertEquals(35, info.targetSdk)
        assertEquals(listOf("android.permission.INTERNET", "android.permission.CAMERA"), info.permissions)
    }

    @Test fun parsesUtf8Manifest() {
        val info = AxmlParser.parseManifest(AxmlTestEncoder.manifest("a.b", 1, "x", 21, 30, emptyList(), utf8 = true))
        assertEquals("a.b", info.packageName); assertEquals(21, info.minSdk); assertEquals("x", info.versionName)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsTextXml() {
        AxmlParser.parseManifest("<manifest/>".toByteArray())
    }

    @Test fun signatureFileDetection() {
        assertTrue(ApkBuilder.isSignatureFile("META-INF/MANIFEST.MF"))
        assertTrue(ApkBuilder.isSignatureFile("META-INF/CERT.SF"))
        assertTrue(ApkBuilder.isSignatureFile("META-INF/cert.rsa"))
        assertFalse(ApkBuilder.isSignatureFile("META-INF/services/x"))
        assertFalse(ApkBuilder.isSignatureFile("res/CERT.RSA"))
    }

    @Test fun alignExtraPadsToBoundary() {
        for (off in 0L..20L) for (align in listOf(4, 16384)) {
            val e = ApkBuilder.alignExtra(off, 14, align)
            assertEquals(0L, (off + 30 + 14 + e.size) % align)
        }
    }

    @Test fun rebuildProducesAlignedApkStructure() {
        val dir = extractedFolder()
        val out = ApkBuilder.rebuiltNameFor(dir)
        assertEquals("demo_rebuilt.apk", out.name)
        val n = ApkBuilder.rebuild(dir, out)
        assertEquals(6, n)
        ZipFile(out).use { z ->
            val names = z.entries().toList().map { it.name }
            assertEquals("AndroidManifest.xml", names.first())
            assertFalse("META-INF/MANIFEST.MF" in names)
            assertTrue("META-INF/services/x" in names)
            assertEquals(ZipEntry.STORED, z.getEntry("resources.arsc").method)
            assertEquals(ZipEntry.STORED, z.getEntry("lib/arm64-v8a/libdemo.so").method)
            assertEquals(ZipEntry.DEFLATED, z.getEntry("classes.dex").method)
        }
        assertEquals(emptyList<String>(), ApkBuilder.misaligned(out))
        val so = ApkBuilder.dataOffsets(out).first { it.name.endsWith(".so") }
        assertEquals(0L, so.dataOffset % 16384)
        assertEquals("com.example.demo", AxmlParser.fromApk(out).packageName)
    }

    @Test fun extractDirIsUnique() {
        val apk = File(tmp, "app.apk").apply { writeText("x") }
        assertEquals("app_extracted", ApkBuilder.extractDirFor(apk).name)
        File(tmp, "app_extracted").mkdirs()
        assertEquals("app_extracted_2", ApkBuilder.extractDirFor(apk).name)
    }

    @Test fun v1SignerNameIsSanitised() {
        assertEquals("VICTORIO", ApkSigning.v1SignerName("victorious-debug"))
        assertEquals("CERT", ApkSigning.v1SignerName("é!"))
        assertEquals("MY_KEY1", ApkSigning.v1SignerName("my_key1"))
    }

    @Test fun generateKeystoreSignAndVerifyV1V2V3() {
        val ks = File(tmp, "debug.p12")
        val pass = "s3cret".toCharArray()
        ApkSigning.generateDebugKeystore(ks, pass)
        val key = ApkSigning.loadKey(ks, pass, ApkSigning.DEBUG_ALIAS)
        assertTrue(key.subject.contains("Victorious"))

        val unsigned = File(tmp, "demo_rebuilt.apk")
        ApkBuilder.rebuild(extractedFolder(), unsigned)
        val signed = ApkSigning.signedNameFor(unsigned)
        assertEquals("demo_rebuilt_signed.apk", signed.name)
        ApkSigning.sign(unsigned, signed, key, ApkSigning.DEBUG_ALIAS)
        val r = ApkSigning.verify(signed, minSdk = 21)
        assertTrue(r.errors.toString(), r.verified)
        assertTrue(r.v1); assertTrue(r.v2); assertTrue(r.v3)
        ZipFile(signed).use { z -> assertTrue(z.getEntry("META-INF/VICTORIO.SF") != null) }
    }

    @Test fun v1OnlySigning() {
        val ks = File(tmp, "k.p12"); val pass = "pw".toCharArray()
        ApkSigning.generateDebugKeystore(ks, pass, alias = "k")
        val unsigned = File(tmp, "u.apk"); ApkBuilder.rebuild(extractedFolder(AxmlTestEncoder.manifest("a.b", 1, "1", 21, 29, emptyList())), unsigned)
        val signed = File(tmp, "s.apk")
        ApkSigning.sign(unsigned, signed, ApkSigning.loadKey(ks, pass, null), "k", SignSchemes(v1 = true, v2 = false, v3 = false))
        val r = ApkSigning.verify(signed)
        assertTrue(r.errors.toString(), r.verified); assertTrue(r.v1); assertFalse(r.v2)
    }

    @Test(expected = IllegalArgumentException::class) fun noSchemesRejected() { SignSchemes(false, false, false) }
}
