package ai.victorious.filemanager.core.apk

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AxmlAndModTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun fixture(): File {
        val src = javaClass.classLoader.getResource("apk/fixture.apk")
            ?: error("Missing test resource apk/fixture.apk")
        val f = tmp.newFile("fixture.apk")
        src.openStream().use { input -> f.outputStream().use { input.copyTo(it) } }
        return f
    }

    private fun key(): SigningKey {
        val ks = tmp.newFile("debug.p12")
        val pass = "test-pass".toCharArray()
        ApkSigning.generateDebugKeystore(ks, pass)
        return ApkSigning.loadKey(ks, pass, ApkSigning.DEBUG_ALIAS)
    }

    @Test fun axmlRoundTripPreservesParseAndIsByteValid() {
        val apk = fixture()
        val raw = ApkResources.read(apk, "AndroidManifest.xml")
        assertTrue(AxmlDecoder.isBinaryXml(raw))
        val before = ManifestEditor.summary(raw)
        assertEquals("com.example.fixture", before.packageName)
        assertEquals(7, before.versionCode)
        assertEquals("1.2.3", before.versionName)
        assertEquals(24, before.minSdk)
        assertEquals(34, before.targetSdk)
        assertFalse(before.debuggable)
        assertTrue(before.permissions.contains("android.permission.INTERNET"))
        assertTrue(before.permissions.contains("android.permission.CAMERA"))

        val reencoded = AxmlEditor.parse(raw).encode()
        assertTrue(AxmlDecoder.isBinaryXml(reencoded))
        val after = ManifestEditor.summary(reencoded)
        assertEquals(before, after)
        // apksig/aapt2-free parser (our AxmlParser) still reads the re-encoded bytes.
        val info = AxmlParser.parseManifest(reencoded)
        assertEquals("com.example.fixture", info.packageName)
        assertEquals(7L, info.versionCode)
        assertEquals("1.2.3", info.versionName)
        assertEquals(24, info.minSdk)
        assertEquals(34, info.targetSdk)
    }

    @Test fun manifestEditsApplyAndSurviveSignVerify() {
        val apk = fixture()
        val plan = ModPlan(
            manifest = ManifestEdit(
                packageName = "com.example.fixture.clone",
                versionCode = 42,
                versionName = "9.9.9",
                minSdk = 26,
                targetSdk = 35,
                debuggable = true,
                addPermissions = listOf("android.permission.ACCESS_FINE_LOCATION"),
                removePermissions = listOf("android.permission.CAMERA"),
                exported = mapOf("com.example.fixture.MainActivity" to false),
            ),
        )
        val out = File(tmp.root, "modded.apk")
        val result = ApkMod.applyTo(apk, plan, out, key())
        assertTrue(result.verify!!.verified)
        assertTrue(result.verify!!.v2 || result.verify!!.v3)
        assertEquals(listOf("AndroidManifest.xml"), result.changedEntries)
        assertEquals("com.example.fixture.clone", result.summary.packageName)
        assertEquals(42, result.summary.versionCode)
        assertEquals("9.9.9", result.summary.versionName)
        assertEquals(26, result.summary.minSdk)
        assertEquals(35, result.summary.targetSdk)
        assertTrue(result.summary.debuggable)
        assertTrue(result.summary.permissions.contains("android.permission.ACCESS_FINE_LOCATION"))
        assertFalse(result.summary.permissions.contains("android.permission.CAMERA"))
        assertTrue(result.summary.permissions.contains("android.permission.INTERNET"))
        // Relative class names were expanded; owned permissions renamed with the package.
        assertTrue(result.summary.permissions.any { it.startsWith("com.example.fixture.clone.") })
        assertFalse(result.summary.permissions.any { it.startsWith("com.example.fixture.") && !it.startsWith("com.example.fixture.clone.") })
        val main = result.summary.components.first { it.className.endsWith(".MainActivity") }
        assertEquals("com.example.fixture.MainActivity", main.className)
        assertEquals(false, main.exported)
        // aapt2 still accepts the sealed APK.
        val aapt = ProcessBuilder(
            "/home/box/android-sdk/build-tools/35.0.0/aapt2", "dump", "xmltree",
            "--file", "AndroidManifest.xml", out.absolutePath,
        ).redirectErrorStream(true).start()
        val text = aapt.inputStream.bufferedReader().readText()
        assertEquals(0, aapt.waitFor())
        assertTrue(text, text.contains("package=\"com.example.fixture.clone\""))
        assertTrue(text, text.contains("versionCode(0x0101021b)=42"))
        assertTrue(text, text.contains("debuggable"))
    }

    @Test fun arscEditRoundTripAndInsideSealedApk() {
        val apk = fixture()
        val bytes = ApkResources.read(apk, "resources.arsc")
        val arsc = Arsc.open(bytes)
        val dump = arsc.packageNames.toString() + " count=" + arsc.strings.size + " names=" + arsc.names + " vals=" +
            arsc.strings.map { it.name + "/" + it.locale + "=" + it.value }
        // Prefer named strings; fall back to any string-typed value for the edit round-trip.
        val target = arsc.strings.firstOrNull { it.name == "app_name" }
            ?: arsc.strings.firstOrNull()
            ?: throw AssertionError("no string resources parsed from fixture.arsc: " + dump)
        val edited = ArscEditor.apply(arsc, mapOf(ArscEditor.ResTarget(target.resId, target.locale) to "FixtureMod"))
        val again = Arsc.open(edited)
        val againVal = again.strings.first { it.resId == target.resId && it.locale == target.locale }.value
        assertEquals("FixtureMod", againVal)

        val shared = ArscEditor.sharedSlots(arsc)
        if (shared.isNotEmpty()) {
            val one = shared.first().resources.first()
            assertTrue(runCatching {
                ArscEditor.apply(arsc, mapOf(ArscEditor.ResTarget(one.resId, one.locale) to "Nope"))
            }.isFailure)
        }

        val out = File(tmp.root, "strings.apk")
        val r = ApkMod.applyTo(
            apk,
            ModPlan(strings = mapOf(ArscEditor.ResTarget(target.resId, target.locale) to "InApk")),
            out,
            key(),
        )
        assertTrue(r.verify!!.verified)
        assertEquals(listOf("resources.arsc"), r.changedEntries)
        assertEquals(
            "InApk",
            Arsc.loadFromTarget(out).strings.first { it.resId == target.resId && it.locale == target.locale }.value,
        )
        // Keep the dump available if names were empty (parser gap vs aapt2).
        if (arsc.strings.none { it.name == "app_name" }) {
            System.err.println("WARN fixture strings lacked app_name: " + dump)
        }
    }

    @Test fun smaliEditRebuildsAndSigns() {
        val apk = fixture()
        val smali = """
            .class public Lcom/example/fixture/MainActivity;
            .super Ljava/lang/Object;
            .method public static greet()Ljava/lang/String;
                .locals 1
                const-string v0, "modded-greeting"
                return-object v0
            .end method
        """.trimIndent()
        val out = File(tmp.root, "smali.apk")
        val r = ApkMod.applyTo(apk, ModPlan(smali = listOf(smali)), out, key())
        assertTrue(r.verify!!.verified)
        assertTrue(r.changedEntries.any { it.startsWith("classes") && it.endsWith(".dex") })
        val dex = DexTools.load(ApkResources.read(out, "classes.dex"))
        val cls = dex.classes.first { it.type == "Lcom/example/fixture/MainActivity;" }
        val text = DexTools.classSmali(cls)
        assertTrue(text, text.contains("modded-greeting"))
    }

    @Test fun invalidPackageRejected() {
        val raw = ApkResources.read(fixture(), "AndroidManifest.xml")
        val bad = runCatching { ManifestEditor.apply(raw, ManifestEdit(packageName = "bad name")) }
        assertTrue(bad.isFailure)
    }
}
