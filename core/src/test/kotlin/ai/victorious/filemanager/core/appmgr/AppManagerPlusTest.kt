package ai.victorious.filemanager.core.appmgr

import ai.victorious.filemanager.core.priv.PrivilegeAvailability
import ai.victorious.filemanager.core.priv.PrivilegeBackend
import ai.victorious.filemanager.core.priv.ShizukuState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AppManagerPlusTest {
    private val sampleExodus = """
        {"trackers":{
          "1":{"name":"Teemo","code_signature":"com.databerries.|com.geolocstation.","categories":["Location"]},
          "2":{"name":"AppsFlyer","code_signature":"com.appsflyer.","categories":["Analytics"]},
          "3":{"name":"Odd","code_signature":"a\\.(b|c)","categories":["Advertisement"]},
          "4":{"name":"Escaped","code_signature":"com\\.escaped\\.sdk","categories":["Advertisement"]},
          "5":{"name":"Empty","code_signature":"","categories":[]}
        }}
    """.trimIndent()

    @Test fun exodusParseSkipsRegexAndUnescapes() {
        val p = ExodusTrackers.parse(sampleExodus)
        val byName = p.signatures.associateBy { it.name }
        assertEquals(listOf("com.databerries.", "com.geolocstation."), byName["Teemo"]!!.prefixes)
        assertEquals(TrackerSignature.Category.LOCATION, byName["Teemo"]!!.category)
        assertEquals(listOf("com.escaped.sdk"), byName["Escaped"]!!.prefixes)
        assertEquals(TrackerSignature.Category.ADVERTISING, byName["Escaped"]!!.category)
        assertFalse("Odd" in byName)
        assertFalse("Empty" in byName)
        assertTrue(p.skippedPatterns >= 1)
    }

    @Test fun exodusMergeKeepsBuiltInAndAddsNew() {
        val merged = ExodusTrackers.merge(TrackerSignatures.ALL, ExodusTrackers.parse(sampleExodus).signatures)
        assertEquals(1, merged.count { it.name == "AppsFlyer" })
        assertTrue(merged.any { it.name == "Teemo" })
        val hits = TrackerScanner.scan(listOf("com.databerries.Sdk", "com.example.Main"), merged)
        assertEquals(listOf("Teemo"), hits.map { it.tracker.name })
    }

    @Test fun libraryScanCountsClasses() {
        val hits = LibrarySignatures.scan(listOf("okhttp3.Call", "okhttp3.Request", "retrofit2.Retrofit", "com.app.Main"))
        assertEquals("OkHttp", hits[0].library.name)
        assertEquals(2, hits[0].classCount)
        assertEquals(setOf("OkHttp", "Retrofit"), hits.map { it.library.name }.toSet())
    }

    @Test fun dexListerReadsApkAndToleratesJunk() {
        val apk = File.createTempFile("fmdex", ".apk")
        ZipOutputStream(apk.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("classes.dex")); z.write(byteArrayOf(1, 2, 3)); z.closeEntry()
            z.putNextEntry(ZipEntry("assets/classes.dex")); z.write(byteArrayOf(1)); z.closeEntry()
        }
        assertEquals(emptyList<String>(), DexClassLister.classes(listOf(apk, File("/nope.apk"))))
        apk.delete()
    }

    @Test fun installPlanBuildsFlagsAndCleansUp() {
        val cmds = InstallCommands.plan("/sdcard/x y.apk", InstallOptions(downgrade = true, testOnly = true, grantAllPermissions = true, installerPackage = "com.android.vending", user = 0, bypassLowTargetSdk = true), 34, 7)
        assertEquals("cp '/sdcard/x y.apk' '/data/local/tmp/fm_install_7.apk'", cmds[0])
        assertEquals("pm install -r -d -t -g -i com.android.vending --user 0 --bypass-low-target-sdk-block '/data/local/tmp/fm_install_7.apk'", cmds[1])
        assertEquals("rm -f '/data/local/tmp/fm_install_7.apk'", cmds[2])
        assertFalse(InstallCommands.flags(InstallOptions(bypassLowTargetSdk = true), 33).contains("--bypass-low-target-sdk-block"))
    }

    @Test(expected = IllegalArgumentException::class) fun installerMustBePackageName() {
        InstallCommands.flags(InstallOptions(installerPackage = "x; rm -rf /"), 34)
    }

    @Test(expected = IllegalArgumentException::class) fun installRejectsSplitBundles() {
        InstallCommands.plan("/sdcard/a.apks", InstallOptions(), 34, 1)
    }

    @Test fun batchPlusCommands() {
        assertEquals(listOf("pm unsuspend --user 0 'a.b'"), BatchOpsPlus.unfreeze(listOf("a.b")).commands)
        assertEquals(listOf("pm uninstall -k --user 0 'a.b'"), BatchOpsPlus.uninstall(listOf("a.b"), keepData = true).commands)
        assertEquals(listOf("pm clear --cache-only --user 0 'a.b'"), BatchOpsPlus.clearCache(listOf("a.b"), PrivilegeBackend.SHIZUKU, 34).commands)
        val rootClear = BatchOpsPlus.clearCache(listOf("a.b"), PrivilegeBackend.ROOT, 30).commands.single()
        assertFalse(rootClear.contains("*"))
        for (dir in listOf(
            "/data/user/0/a.b/cache",
            "/data/user/0/a.b/code_cache",
            "/data/user_de/0/a.b/cache",
            "/data/user_de/0/a.b/code_cache",
        )) {
            assertTrue(rootClear.contains(BatchOpsPlus.clearCacheDir(dir)))
            assertTrue(rootClear.contains("[ ! -L '$dir' ]"))
        }
        assertEquals(listOf("cmd appops reset 'a.b'"), BatchOpsPlus.resetAppOps(listOf("a.b")).commands)
    }

    @Test fun clearCacheGating() {
        assertTrue(runCatching { BatchOpsPlus.clearCache(listOf("a.b"), PrivilegeBackend.SHIZUKU, 33) }.isFailure)
        assertTrue(runCatching { BatchOpsPlus.clearCache(listOf("a.b"), PrivilegeBackend.NORMAL, 34) }.isFailure)
        assertTrue(runCatching { BatchOpsPlus.clearCache(listOf("a;b"), PrivilegeBackend.ROOT, 34) }.isFailure)
        assertTrue(runCatching { BatchOpsPlus.clearCache(listOf(".."), PrivilegeBackend.ROOT, 34) }.isFailure)
        assertTrue(runCatching { BatchOpsPlus.clearCache(listOf("."), PrivilegeBackend.ROOT, 34) }.isFailure)
        assertTrue(runCatching { BatchOpsPlus.clearCache(listOf("a.b"), PrivilegeBackend.ROOT, 34, user = -1) }.isFailure)
    }

    @Test fun appOpsForegroundAndCatalog() {
        val m = AppOpsManagerOps.parse("CAMERA: foreground; time=+1m\nRECORD_AUDIO: deny")
        assertEquals(AppOpsManagerOps.Mode.FOREGROUND, m["CAMERA"])
        assertTrue(AppOpCatalog.valid("CAMERA"))
        assertFalse(AppOpCatalog.valid("camera; reboot"))
    }

    @Test fun netUsageMergeAndFormat() {
        val m = NetUsageAggregator.merge(listOf(NetUsage(10, mobileRx = 100), NetUsage(10, wifiTx = 50), NetUsage(11), NetUsage(12, wifiRx = 2000)))
        assertEquals(listOf(12, 10), m.map { it.uid })
        assertEquals(150, m[1].total)
        assertEquals("1.5 KB", NetUsageAggregator.formatBytes(1536))
        assertEquals("999 B", NetUsageAggregator.formatBytes(999))
    }

    @Test fun privilegeBannerStates() {
        fun av(root: Boolean = false, rootOn: Boolean = false, shz: Boolean = false, perm: Boolean = false, shzOn: Boolean = false) =
            PrivilegeAvailability(root, rootOn, shz, perm, shzOn)
        assertEquals(PrivilegeStatus.Level.ROOT, PrivilegeStatus.banner(av(root = true, rootOn = true), ShizukuState.NOT_INSTALLED).level)
        assertEquals(PrivilegeStatus.Level.SHIZUKU, PrivilegeStatus.banner(av(shz = true, perm = true, shzOn = true), ShizukuState.GRANTED).level)
        val denied = PrivilegeStatus.banner(av(shz = true, shzOn = true), ShizukuState.DENIED)
        assertEquals(PrivilegeStatus.Level.LIMITED, denied.level)
        assertEquals("Grant Shizuku", denied.action)
        assertEquals("Open settings", PrivilegeStatus.banner(av(), ShizukuState.NOT_INSTALLED).action)
        assertNull(PrivilegeStatus.blockReason(PrivilegeStatus.Need.ELEVATED, PrivilegeBackend.SHIZUKU))
        assertEquals("Needs Root", PrivilegeStatus.blockReason(PrivilegeStatus.Need.ROOT, PrivilegeBackend.SHIZUKU))
    }
}
