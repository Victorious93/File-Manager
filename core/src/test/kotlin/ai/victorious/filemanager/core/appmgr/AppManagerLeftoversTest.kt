package ai.victorious.filemanager.core.appmgr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppManagerLeftoversTest {
    @Test fun splitInstallUsesOneSessionWithTheChosenOptions() {
        val apks = listOf(SplitApk("/sdcard/x/base.apk", 1000), SplitApk("/sdcard/x/split_config.arm64_v8a.apk", 200))
        val cmds = SplitInstallCommands.plan(apks, InstallOptions(downgrade = true, grantAllPermissions = true, installerPackage = "com.android.vending"), 34, 7)
        assertEquals(2, cmds.size)
        val s = cmds[0]
        assertTrue(s, s.contains("pm install-create -r -d -g -i com.android.vending -S 1200"))
        assertTrue(s.contains("cp '/sdcard/x/base.apk' '/data/local/tmp/fm_split_7'/0.apk"))
        assertTrue(s.contains("pm install-write -S 1000 \$sid 0.apk '/data/local/tmp/fm_split_7'/0.apk"))
        assertTrue(s.contains("pm install-write -S 200 \$sid 1.apk"))
        assertTrue(s.contains("pm install-commit \$sid"))
        assertTrue("abandons a half-made session", s.endsWith("|| { [ -n \"\$sid\" ] && pm install-abandon \$sid; false; }"))
        assertEquals("rm -rf '/data/local/tmp/fm_split_7'", cmds[1])
    }

    @Test fun splitInstallRejectsBadInput() {
        fun fails(f: () -> Unit) = assertTrue(runCatching(f).isFailure)
        fails { SplitInstallCommands.plan(emptyList(), InstallOptions(), 34, 1) }
        fails { SplitInstallCommands.plan(listOf(SplitApk("/a.apks", 10)), InstallOptions(), 34, 1) }
        fails { SplitInstallCommands.plan(listOf(SplitApk("/a.apk", 0)), InstallOptions(), 34, 1) }
        fails { SplitInstallCommands.plan(listOf(SplitApk("/a.apk", 5)), InstallOptions(installerPackage = "x; rm -rf /"), 34, 1) }
        // Paths with quotes stay one shell word.
        assertTrue(SplitInstallCommands.plan(listOf(SplitApk("/sdcard/it's.apk", 5)), InstallOptions(), 30, 1)[0].contains("'/sdcard/it'\"'\"'s.apk'"))
        // The Android 14 flag is only added on 14+.
        assertFalse(SplitInstallCommands.plan(listOf(SplitApk("/a.apk", 5)), InstallOptions(bypassLowTargetSdk = true), 33, 1)[0].contains("bypass"))
    }

    @Test fun bundleEntriesPutBaseFirstAndRefuseTraversal() {
        val e = SplitInstallCommands.bundleApkEntries(listOf("split_config.en.apk", "manifest.json", "base.apk", "icon.png", "dir/"))
        assertEquals(listOf("base.apk", "split_config.en.apk"), e)
        assertEquals(listOf("ok.apk"), SplitInstallCommands.bundleApkEntries(listOf("../evil.apk", "/abs.apk", "ok.apk")))
        assertTrue(runCatching { SplitInstallCommands.bundleApkEntries(listOf("a.txt")) }.isFailure)
        assertEquals("split_3.apk", SplitInstallCommands.safeName("x/we ird;name.apk", 3))
        assertEquals("config.xx.apk", SplitInstallCommands.safeName("a/config.xx.apk", 0))
    }

    @Test fun debloatListParsing() {
        val text = """
            # my list
            com.facebook.appmanager   # preinstalled
            com.facebook.system, Facebook, remove
            pm uninstall --user 0 com.samsung.android.bixby.agent
            com.facebook.appmanager
            not a package
            ; comment
            // another
        """.trimIndent()
        val p = Debloater.parse(text)
        assertEquals(listOf("com.facebook.appmanager", "com.facebook.system", "com.samsung.android.bixby.agent"), p.packages)
        assertEquals(1, p.duplicates)
        assertEquals(listOf(6), p.invalidLines)
    }

    @Test fun debloatPlanOnlyTouchesInstalledUnprotectedPackagesAndCanBeUndone() {
        val list = listOf("com.bloat.one", "com.android.systemui", "com.not.here", "ai.victorious.filemanager", "com.bloat.two")
        val installed = setOf("com.bloat.one", "com.bloat.two", "com.android.systemui", "ai.victorious.filemanager")
        val plan = Debloater.plan(list, installed, Debloater.Action.UNINSTALL_FOR_USER, keep = setOf("ai.victorious.filemanager"))
        assertEquals(listOf("com.bloat.one", "com.bloat.two"), plan.targets)
        assertEquals(listOf("com.not.here"), plan.notInstalled)
        assertEquals(listOf("com.android.systemui", "ai.victorious.filemanager"), plan.protectedSkipped)
        assertEquals(listOf("pm uninstall -k --user 0 'com.bloat.one'", "pm uninstall -k --user 0 'com.bloat.two'"), plan.batch.commands)
        assertEquals("cmd package install-existing --user 0 'com.bloat.one'", plan.undo.commands[0])
        assertEquals("pm suspend --user 0 'x.y'", Debloater.Action.FREEZE.command("x.y"))
        assertEquals("pm unsuspend --user 0 'x.y'", Debloater.Action.FREEZE.undo("x.y"))
        assertEquals("pm disable-user --user 0 'x.y'", Debloater.Action.DISABLE.command("x.y"))
        assertEquals("pm enable --user 0 'x.y'", Debloater.Action.DISABLE.undo("x.y"))
    }

    @Test fun logRingKeepsTheNewestLines() {
        val r = LogRing(3)
        (1..5).forEach { r.add(LogcatParser.parse("10-13 12:34:56.789  1  2 I T: m$it")) }
        assertEquals(listOf("m3", "m4", "m5"), r.snapshot().map { it.message })
        assertEquals(2L, r.dropped)
        r.clear()
        assertEquals(0, r.size)
    }

    @Test fun liveLogcatCommand() {
        assertEquals(listOf("logcat", "-v", "threadtime", "-T", "200"), LiveLogcat.command().toList())
        assertEquals(listOf("logcat", "-v", "threadtime", "-T", "5000", "--pid=42"), LiveLogcat.command(99_999, 42).toList())
        assertEquals(1234, LiveLogcat.parsePidof("1234 5678\n"))
        assertNull(LiveLogcat.parsePidof(""))
    }
}
