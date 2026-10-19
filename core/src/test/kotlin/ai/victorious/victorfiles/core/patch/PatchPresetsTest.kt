package ai.victorious.victorfiles.core.patch

import ai.victorious.victorfiles.core.apk.ApkResources
import ai.victorious.victorfiles.core.apk.ApkSigning
import ai.victorious.victorfiles.core.apk.DexTools
import ai.victorious.victorfiles.core.apk.ManifestEditor
import ai.victorious.victorfiles.core.apk.SigningKey
import ai.victorious.victorfiles.core.apk.SmaliAssembler
import ai.victorious.victorfiles.core.apk.SmaliText
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PatchPresetsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun fixture(): File {
        val src = javaClass.classLoader.getResource("apk/fixture.apk") ?: error("missing fixture.apk")
        val f = tmp.newFile("fixture.apk")
        src.openStream().use { i -> f.outputStream().use { i.copyTo(it) } }
        return f
    }

    private fun gate(): PatchGate = PatchGate(File(tmp.newFolder("gate"), "auth.json"))

    private fun key(): SigningKey {
        val ks = tmp.newFile("debug.p12")
        val pass = "test-pass".toCharArray()
        ApkSigning.generateDebugKeystore(ks, pass)
        return ApkSigning.loadKey(ks, pass, ApkSigning.DEBUG_ALIAS)
    }

    // ---- Gate logic ----

    @Test fun gateRecordsAndEnforcesPerPackageAndCert() {
        val g = gate()
        val pkg = "com.example.app"
        val cert = "aa".repeat(32)
        assertFalse(g.isAuthorized(pkg, cert))
        try { g.requireAuthorized(pkg, cert); fail("expected refusal") } catch (e: PatchNotAuthorizedException) {}
        g.authorize(pkg, cert, note = "my app")
        assertTrue(g.isAuthorized(pkg, cert))
        g.requireAuthorized(pkg, cert)
        assertFalse(g.isAuthorized(pkg, "bb".repeat(32)))
        assertTrue(g.isAuthorized(pkg, "AA".repeat(32).chunked(2).joinToString(":")))
        assertTrue(g.revoke(pkg, cert))
        assertFalse(g.isAuthorized(pkg, cert))
    }

    @Test fun gatePersistsToDisk() {
        val file = File(tmp.newFolder("g2"), "auth.json")
        PatchGate(file).authorize("p.kg", "UNSIGNED")
        assertTrue(PatchGate(file).isAuthorized("p.kg", "unsigned"))
        assertTrue(PatchGate.CONFIRMATION_TEXT.contains("own", ignoreCase = true))
    }

    // ---- Manifest flag round-trip ----

    @Test fun manifestFlagsRoundTripThroughPreviewAndApply() {
        val apk = fixture()
        val g = gate()
        val s = PatchSession(apk, g)
        assertEquals("com.example.fixture", s.packageName)
        assertEquals(false, s.summary.allowBackup)
        assertNull(s.summary.usesCleartextTraffic)

        val steps = listOf(
            PatchStep.SetManifestFlag(ManifestFlag.DEBUGGABLE, true),
            PatchStep.SetManifestFlag(ManifestFlag.ALLOW_BACKUP, true),
            PatchStep.SetManifestFlag(ManifestFlag.CLEARTEXT_TRAFFIC, true),
            PatchStep.SetManifestFlag(ManifestFlag.EXTRACT_NATIVE_LIBS, false),
        )
        val preview = s.preview(steps)
        assertTrue(preview.any { it.entry == "AndroidManifest.xml" && it.diff.isNotBlank() })

        val out = File(tmp.root, "patched.apk")
        try { s.apply(steps, out, key()); fail("expected gate refusal") } catch (e: PatchNotAuthorizedException) {}

        g.authorize(s.packageName, s.certSha256, "owner")
        val applied = s.apply(steps, out, key())
        assertTrue(applied.result.verify!!.verified)

        val after = ManifestEditor.summary(ApkResources.read(out, "AndroidManifest.xml"))
        assertTrue(after.debuggable)
        assertEquals(true, after.allowBackup)
        assertEquals(true, after.usesCleartextTraffic)
        assertEquals(false, after.extractNativeLibs)
    }

    // ---- Undo ----

    @Test fun undoRestoresOriginalApkBytes() {
        val apk = fixture()
        val original = apk.readBytes()
        val g = gate()
        val s = PatchSession(apk, g)
        g.authorize(s.packageName, s.certSha256)
        val out = File(tmp.root, "out.apk")
        val applied = s.apply(listOf(PatchStep.SetManifestFlag(ManifestFlag.DEBUGGABLE, true)), out, key = null)
        assertNotEquals(original.toList(), out.readBytes().toList())
        applied.undo()
        assertEquals(original.toList(), out.readBytes().toList())
    }

    // ---- Smali transforms ----

    private val sampleClass = """
        .class public Lcom/example/Guard;
        .super Ljava/lang/Object;

        .method public verifySignature(Landroid/content/pm/PackageManager;Ljava/lang/String;)Z
            .locals 2
            const/4 v1, 0x0
            invoke-virtual {p1, p2, v1}, Landroid/content/pm/PackageManager;->getPackageInfo(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;
            move-result-object v0
            const/4 v0, 0x0
            return v0
        .end method

        .method public showError()V
            .registers 3
            const/4 v0, 0x0
            const-string v1, "nope"
            invoke-static {v0, v1}, Landroid/widget/Toast;->makeText(Landroid/content/Context;Ljava/lang/CharSequence;I)Landroid/widget/Toast;
            move-result-object v0
            invoke-virtual {v0}, Landroid/widget/Toast;->show()V
            return-void
        .end method
    """.trimIndent()

    @Test fun neutralizeForcesConstantReturnAndReassembles() {
        val sig = "verifySignature(Landroid/content/pm/PackageManager;Ljava/lang/String;)Z"
        val before = SmaliText.method(sampleClass, sig)!!
        assertTrue(before.contains("getPackageInfo"))
        val after = SmaliPatch.neutralizeMethod(sampleClass, sig, boolValue = true)
        val m = SmaliText.method(after, sig)!!
        assertTrue(m.contains("const/4 v0, 0x1"))
        assertTrue(m.contains("return v0"))
        assertFalse(m.contains("getPackageInfo"))
        val cls = SmaliAssembler.assembleClass(after)
        assertEquals("Lcom/example/Guard;", cls.type)
    }

    @Test fun removeToastsStripsShowCalls() {
        val (after, n) = SmaliPatch.removeToasts(sampleClass)
        assertEquals(1, n)
        assertFalse(after.contains("Landroid/widget/Toast;->show()V"))
        assertTrue(after.contains("Toast;->makeText"))
        SmaliAssembler.assembleClass(after)
    }

    @Test fun injectEntryLogAddsLogCallAndReassembles() {
        val sig = "showError()V"
        val after = SmaliPatch.injectEntryLog(sampleClass, sig, "FM", "entered showError")
        val m = SmaliText.method(after, sig)!!
        assertTrue(m.contains("Landroid/util/Log;->d(Ljava/lang/String;Ljava/lang/String;)I"))
        assertTrue(m.contains("entered showError"))
        SmaliAssembler.assembleClass(after)
    }

    @Test fun registersToLocalsConversionIsExactAndAssembles() {
        val sig = "f(Landroid/content/Context;J)V"
        val method = """
            .method public f(Landroid/content/Context;J)V
                .registers 6
                const/4 v0, 0x1
                invoke-virtual {p1}, Landroid/content/Context;->getPackageName()Ljava/lang/String;
                move-result-object v1
                return-void
            .end method
        """.trimIndent()
        // regs=6, instance + object(1) + long(2) = 4 param words -> locals = 2; p0=v2(this), p1=v3(ctx), p2=v4(long)
        val local = SmaliPatch.toLocalsForm(method, sig)
        assertTrue(local.contains(".locals 2"))
        // v0, v1 are locals (unchanged); params referenced as p-registers.
        assertTrue(local.contains("const/4 v0, 0x1"))
        assertTrue(local.contains("{p1}"))
        SmaliAssembler.assembleClass(
            ".class public LX;\n.super Ljava/lang/Object;\n" + local,
        )
    }

    @Test fun parseParamWidthsHandlesWideAndArrays() {
        assertEquals(listOf(1, 2, 1, 1), SmaliPatch.parseParamWidths("f(IJLjava/lang/String;[I)V"))
        assertEquals(emptyList<Int>(), SmaliPatch.parseParamWidths("g()V"))
    }

    // ---- Pattern detection ----

    @Test fun detectFlagsSignatureCheckByApiAndName() {
        val matches = SmaliPatch.detect("Lcom/example/Guard;", sampleClass)
        val sig = matches.firstOrNull { it.signature.startsWith("verifySignature") }
        assertTrue("should flag verifySignature", sig != null)
        assertEquals("Z", sig!!.returnType)
        assertTrue(sig.reasons.any { it.contains("signature API") })
        assertTrue(sig.reasons.any { it.contains("name hint") })
        assertTrue(matches.none { it.signature.startsWith("showError") })
    }

    @Test fun detectFindsLicenseCheckByApi() {
        val lic = """
            .class public Lcom/example/Lic;
            .super Ljava/lang/Object;
            .method public check()Z
                .locals 1
                invoke-static {}, Lcom/android/vending/licensing/LicenseChecker;->checkAccess()V
                const/4 v0, 0x1
                return v0
            .end method
        """.trimIndent()
        val m = SmaliPatch.detect("Lcom/example/Lic;", lic, setOf(SmaliPatch.Kind.LICENSE))
        assertEquals(1, m.size)
        assertTrue(m.first().reasons.any { it.contains("license API") })
    }

    // ---- End-to-end smali patch on the real fixture dex ----

    @Test fun smaliPatchRebuildsDexInApk() {
        val apk = fixture()
        val g = gate()
        val s = PatchSession(apk, g)
        g.authorize(s.packageName, s.certSha256)
        val src = DexTools.listDex(apk).first()
        val dex = DexTools.load(src)
        val cls = dex.classes.first { c -> SmaliText.methods(DexTools.classSmali(c)).any { bodied(it.text) } }
        val smali = DexTools.classSmali(cls)
        val method = SmaliText.methods(smali).first { bodied(it.text) }
        val step = PatchStep.NeutralizeMethod(cls.type, method.signature)
        val preview = s.preview(listOf(step))
        assertTrue(preview.any { it.summary.startsWith("smali:") && it.diff.isNotBlank() })
        val out = File(tmp.root, "smali-out.apk")
        val applied = s.apply(listOf(step), out, key = null)
        assertTrue(applied.result.changedEntries.any { it.endsWith(".dex") })
    }

    private fun bodied(methodText: String): Boolean {
        val header = methodText.lines().first()
        return !header.contains(" abstract ") && !header.contains(" native ") &&
            (methodText.contains(".registers ") || methodText.contains(".locals "))
    }

    @Test fun trustUserCaStepIsPreviewedAsDeferredAndRefusedOnApply() {
        val apk = fixture()
        val g = gate()
        val s = PatchSession(apk, g)
        g.authorize(s.packageName, s.certSha256)
        val change = s.preview(listOf(PatchStep.TrustUserCertificates)).single()
        assertFalse(change.applicable)
        try {
            s.apply(listOf(PatchStep.TrustUserCertificates), File(tmp.root, "x.apk"), key = null)
            fail("expected refusal")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("deferred", ignoreCase = true))
        }
    }
}
