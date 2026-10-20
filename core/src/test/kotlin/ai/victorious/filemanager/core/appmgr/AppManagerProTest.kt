package ai.victorious.filemanager.core.appmgr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComponentBlockerTest {
    @Test fun pmCommandsQuote() {
        assertTrue(ComponentBlocker.pmDisable("com.a", "com.a.B").contains("'com.a/com.a.B'"))
        assertTrue(ComponentBlocker.pmEnable("com.a", "com.a.B", 10).contains("--user 10"))
    }

    @Test fun ifwXmlHasOnlyNonProviders() {
        val blocked = listOf(
            AppComponent("com.x", "com.x.Act", ComponentType.ACTIVITY, true),
            AppComponent("com.x", "com.x.Svc", ComponentType.SERVICE, true),
            AppComponent("com.x", "com.x.Recv", ComponentType.RECEIVER, true),
            AppComponent("com.x", "com.x.Prov", ComponentType.PROVIDER, true),
        )
        val xml = ComponentBlocker.ifwRules("com.x", blocked)
        assertTrue(xml.contains("<activity"))
        assertTrue(xml.contains("<service"))
        assertTrue(xml.contains("<broadcast"))
        assertFalse(xml.contains("<provider"))
        assertTrue(xml.contains("com.x/com.x.Act"))
        assertEquals(listOf(blocked[3]), ComponentBlocker.providersNeedPmDisable(blocked))
        assertTrue(ComponentBlocker.writeIfwCommands("com.x", xml).any { it.contains("chmod 644") })
        assertTrue(ComponentBlocker.removeIfwCommand("com.x").contains("/data/system/ifw/com.x.xml"))
    }

    @Test fun ifwEscapesXml() {
        val xml = ComponentBlocker.ifwRules("a", listOf(AppComponent("a", "a.B\"C", ComponentType.ACTIVITY, true)))
        assertTrue(xml.contains("&quot;"))
        assertFalse(xml.contains("B\"C"))
    }
}

class PermissionAppOpsTest {
    @Test fun parseGranted() {
        val dump = """
            Package [com.a]:
              requested permissions:
                android.permission.CAMERA
              install permissions:
              runtime permissions:
                android.permission.CAMERA: granted=true, flags=0x0
                android.permission.READ_CONTACTS: granted=false
              User 0:
                runtime permissions:
                  android.permission.RECORD_AUDIO: granted=true
        """.trimIndent()
        val g = PermissionOps.parseGranted(dump)
        assertEquals(true, g["android.permission.CAMERA"])
        assertEquals(false, g["android.permission.READ_CONTACTS"])
        assertEquals(true, g["android.permission.RECORD_AUDIO"])
        assertTrue(PermissionOps.grant("com.a", "android.permission.CAMERA").startsWith("pm grant"))
        assertTrue(PermissionOps.revoke("com.a", "android.permission.CAMERA").startsWith("pm revoke"))
    }

    @Test fun parseAppOps() {
        val text = """
            Uid 10001:
              CAMERA: deny; time=+1d
              COARSE_LOCATION: allow
              READ_CONTACTS: ignore
              WRITE_SETTINGS: default
        """.trimIndent()
        val m = AppOpsManagerOps.parse(text)
        assertEquals(AppOpsManagerOps.Mode.DENY, m["CAMERA"])
        assertEquals(AppOpsManagerOps.Mode.ALLOW, m["COARSE_LOCATION"])
        assertEquals(AppOpsManagerOps.Mode.IGNORE, m["READ_CONTACTS"])
        assertEquals(AppOpsManagerOps.Mode.DEFAULT, m["WRITE_SETTINGS"])
        assertTrue(AppOpsManagerOps.set("com.a", "CAMERA", AppOpsManagerOps.Mode.DENY).contains("deny"))
    }
}

class TrackerScanTest {
    @Test fun matchesPrefixesOnly() {
        val classes = listOf(
            "com.google.firebase.analytics.FirebaseAnalytics",
            "com.facebook.ads.AdView",
            "com.facebook.adsfoo.NotATracker",
            "com.appsflyer.AFInAppEventType",
            "com.example.MyActivity",
            "io.sentry.Sentry",
        )
        val hits = TrackerScanner.scan(classes)
        val names = hits.map { it.tracker.name }.toSet()
        assertTrue(names.contains("Google Firebase Analytics"))
        assertTrue(names.contains("Facebook Audience Network"))
        assertTrue(names.contains("AppsFlyer"))
        assertTrue(names.contains("Sentry"))
        assertFalse(names.any { it.contains("adsfoo", ignoreCase = true) })
        assertEquals(listOf("com.facebook.ads.AdView"), hits.first { it.tracker.name.startsWith("Facebook Audience") }.matchedClasses)
    }

    @Test fun descriptorConversion() {
        assertEquals("com.foo.Bar", TrackerScanner.descriptorToClass("Lcom/foo/Bar;"))
    }

    @Test fun trackerComponents() {
        val comps = listOf(
            AppComponent("com.x", "com.google.android.gms.ads.AdActivity", ComponentType.ACTIVITY),
            AppComponent("com.x", "com.x.Main", ComponentType.ACTIVITY),
        )
        assertEquals(1, TrackerScanner.trackerComponents(comps).size)
    }

    @Test fun signaturesAreSelfBuilt() {
        assertTrue(TrackerSignatures.ALL.size >= 30)
        assertTrue(TrackerSignatures.ALL.all { it.prefixes.isNotEmpty() && it.prefixes.all { p -> p.endsWith('.') } })
    }
}

class LogcatTest {
    @Test fun parseThreadtime() {
        val line = "10-13 12:34:56.789  1234  5678 I ActivityManager: Start proc com.a"
        val p = LogcatParser.parse(line)
        assertEquals('I', p.level)
        assertEquals("ActivityManager", p.tag)
        assertEquals(1234, p.pid)
        assertTrue(p.message.contains("com.a"))
        assertEquals("not a log", LogcatParser.parse("not a log").raw)
    }

    @Test fun filter() {
        val lines = LogcatParser.parseAll(
            """
            10-13 12:00:00.000  1  1 I TagA: hello
            10-13 12:00:01.000  2  2 E TagB: boom
            10-13 12:00:02.000  1  1 D TagA: quiet
            """.trimIndent(),
        )
        assertEquals(1, LogcatFilter.apply(lines, LogFilter(levels = setOf('E'))).size)
        assertEquals(2, LogcatFilter.apply(lines, LogFilter(tagContains = "TagA")).size)
        assertEquals(1, LogcatFilter.apply(lines, LogFilter(messageContains = "boom")).size)
        assertEquals(2, LogcatFilter.apply(lines, LogFilter(pid = 1)).size)
        assertEquals(1, LogcatFilter.apply(lines, LogFilter(packageHint = "boom")).size)
        assertTrue(LogcatFilter.export(lines).contains("TagA: hello"))
    }
}

class UsageRunningTest {
    @Test fun usageMergeSortFormat() {
        val rows = listOf(
            AppUsage("a", 5_000, 100, 2),
            AppUsage("a", 3_000, 200, 1),
            AppUsage("b", 10_000, 50, 9),
        )
        val m = UsageAggregator.merge(rows)
        assertEquals(8_000L, m.first { it.packageName == "a" }.totalForegroundMs)
        assertEquals(200L, m.first { it.packageName == "a" }.lastUsedEpochMs)
        assertEquals(3, m.first { it.packageName == "a" }.launchCount)
        assertEquals(listOf("b", "a"), UsageAggregator.sort(m, UsageSort.TIME).map { it.packageName })
        assertEquals(listOf("a", "b"), UsageAggregator.sort(m, UsageSort.LAST_USED).map { it.packageName })
        assertEquals("1h 1m", UsageAggregator.formatDuration(3_660_000))
        assertEquals("2m 5s", UsageAggregator.formatDuration(125_000))
        assertEquals("7s", UsageAggregator.formatDuration(7_000))
    }

    @Test fun parsePsAndPackageOf() {
        val text = """
            USER           PID NAME
            u0_a123       4242 com.example.app
            root             1 init
            u0_a200       5555 com.example.app:remote
        """.trimIndent()
        val procs = RunningApps.parsePs(text)
        assertEquals(3, procs.size)
        assertEquals("com.example.app", procs[0].packageName)
        assertEquals("com.example.app", procs[2].packageName)
        assertNull(procs[1].packageName)
        assertEquals("com.foo", RunningApps.packageOf("com.foo:push"))
        assertNull(RunningApps.packageOf("zygote64"))
        assertTrue(RunningApps.forceStop("com.a").contains("force-stop"))
        assertEquals("kill -9 42", RunningApps.killPid(42))
    }
}

class ProfileBatchTest {
    @Test fun profileRoundTripAndPlan() {
        val p = AppProfile(
            "p1", "Work mode", "disable games",
            listOf(
                ProfileAction.DisablePackage("com.game"),
                ProfileAction.BlockComponent("com.x", "com.x.Ad", "activity"),
                ProfileAction.RevokePermission("com.x", "android.permission.CAMERA"),
                ProfileAction.SetAppOp("com.x", "CAMERA", "deny"),
                ProfileAction.ForceStop("com.x"),
            ),
            createdAtMs = 1,
        )
        val round = ProfileCodec.decode(ProfileCodec.encode(listOf(p))).single()
        assertEquals(p, round)
        val plan = ProfileRunner.plan(p)
        assertEquals(5, plan.commands.size)
        assertTrue(plan.summary[0].contains("Disable com.game"))
        assertTrue(plan.commands[3].contains("appops set"))
    }

    @Test fun batchPlansNeedConfirm() {
        val b = BatchOps.disable(listOf("a", "b", "c"))
        assertEquals(3, b.commands.size)
        assertTrue(b.summary.contains("Disable apps (3)"))
        assertEquals(2, BatchOps.blockComponents(listOf(
            AppComponent("p", "c1", ComponentType.ACTIVITY),
            AppComponent("p", "c2", ComponentType.SERVICE),
        )).commands.size)
    }

    @Test fun appStateCommands() {
        assertTrue(AppState.suspend("com.a").contains("pm suspend"))
        assertTrue(AppState.clearData("com.a").contains("pm clear"))
        assertTrue(AppState.enable("com.a", 10).contains("--user 10"))
    }
}
