package ai.victorious.victorfiles.core.priv

import ai.victorious.victorfiles.core.shell.ShellResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ShizukuStateMachineTest {
    private fun r(installed: Boolean, alive: Boolean, granted: Boolean, preV11: Boolean = false) =
        ShizukuStateMachine.resolve(ShizukuProbe(installed, alive, granted, preV11))

    @Test fun fourStates() {
        assertEquals(ShizukuState.NOT_INSTALLED, r(false, false, false))
        assertEquals(ShizukuState.NOT_RUNNING, r(true, false, false))
        assertEquals(ShizukuState.DENIED, r(true, true, false))
        assertEquals(ShizukuState.GRANTED, r(true, true, true))
    }

    @Test fun liveBinderWithoutPackageIsSuiAndCounts() {
        assertEquals(ShizukuState.GRANTED, r(false, true, true))
    }

    @Test fun permissionIgnoredWhenNotRunning() {
        assertEquals(ShizukuState.NOT_RUNNING, r(true, false, true))
    }

    @Test fun preV11IsDenied() = assertEquals(ShizukuState.DENIED, r(true, true, true, preV11 = true))

    @Test fun transitions() {
        assertEquals(ShizukuState.GRANTED, ShizukuStateMachine.onPermissionResult(ShizukuState.DENIED, true))
        assertEquals(ShizukuState.DENIED, ShizukuStateMachine.onPermissionResult(ShizukuState.GRANTED, false))
        assertEquals(ShizukuState.NOT_RUNNING, ShizukuStateMachine.onPermissionResult(ShizukuState.NOT_RUNNING, true))
        assertEquals(ShizukuState.NOT_RUNNING, ShizukuStateMachine.onBinderDead(ShizukuState.GRANTED))
        assertEquals(ShizukuState.NOT_INSTALLED, ShizukuStateMachine.onBinderDead(ShizukuState.NOT_INSTALLED))
    }

    @Test fun onlyDeniedCanRequest() {
        assertTrue(ShizukuStateMachine.canRequestPermission(ShizukuState.DENIED))
        ShizukuState.values().filter { it != ShizukuState.DENIED }.forEach {
            assertFalse(ShizukuStateMachine.canRequestPermission(it))
        }
    }

    @Test fun copyIsDistinctPerState() {
        assertEquals(4, ShizukuState.values().map { ShizukuCopy.title(it) }.toSet().size)
        assertNull(ShizukuCopy.action(ShizukuState.GRANTED))
        assertTrue(ShizukuCopy.detail(ShizukuState.NOT_RUNNING).contains("Wireless debugging"))
        assertEquals("moe.shizuku.manager.permission.API_V23", ShizukuCopy.PERMISSION)
    }
}

/** Fake of the bound UserService: records commands, returns scripted results. */
private class FakeRunner(var result: ShellResult = ShellResult(0, "", "")) : CommandRunner {
    val calls = mutableListOf<Pair<String, String?>>()
    override fun run(command: String, stdin: String?): ShellResult { calls += command to stdin; return result }
}

class PrivilegedOpsTest {
    @Test fun opsMapToCommands() {
        val f = FakeRunner()
        val ops = ShellPrivilegedOps(PrivilegeBackend.SHIZUKU, f)
        ops.chmod("/data/x", "644")
        ops.chown("/data/x", "system:system")
        ops.remount("/system", true)
        ops.installPackage("/sdcard/a b.apk")
        ops.uninstallPackage("com.example.app")
        ops.writeText("/data/local/tmp/t", "hi")
        assertEquals(
            listOf(
                "chmod 644 '/data/x'",
                "chown system:system '/data/x'",
                "mount -o remount,rw '/system'",
                "pm install -r '/sdcard/a b.apk'",
                "pm uninstall com.example.app",
                "cat > '/data/local/tmp/t'",
            ),
            f.calls.map { it.first },
        )
        assertEquals("hi", f.calls.last().second)
    }

    @Test fun listParsesServiceOutput() {
        val ops = ShellPrivilegedOps(PrivilegeBackend.SHIZUKU, FakeRunner(ShellResult(0, "d|0|1|app\nf|12|2|a.txt\n", "")))
        val rows = ops.list("/data")
        assertEquals(listOf("app", "a.txt"), rows.map { it.name })
        assertTrue(rows[0].isDirectory)
    }

    @Test fun failureDoesNotLeakStdout() {
        val ops = ShellPrivilegedOps(PrivilegeBackend.SHIZUKU, FakeRunner(ShellResult(1, "SECRET", "denied")))
        try { ops.readText("/data/x"); fail() } catch (e: PrivilegedOpException) {
            assertEquals("denied", e.message)
            assertFalse(e.message!!.contains("SECRET"))
        }
    }

    @Test fun rejectsUnsafeArgs() {
        listOf(
            { PowerCommands.chown("/x", "root;reboot") },
            { PowerCommands.remount("/data", true) },
            { PowerCommands.uninstall("a;rm -rf /") },
        ).forEach { try { it(); fail() } catch (_: IllegalArgumentException) {} }
    }

    @Test fun unprivilegedNeverCrashesAppJustExplains() {
        val ops = UnprivilegedOps { "Start Shizuku" }
        try { ops.list("/data"); fail() } catch (e: PrivilegedOpException) { assertEquals("Start Shizuku", e.message) }
    }

    @Test fun gateBlocksUnconfirmedAndIsSingleUse() {
        val f = FakeRunner()
        val gate = ConfirmGate()
        val ops = GatedPrivilegedOps(ShellPrivilegedOps(PrivilegeBackend.SHIZUKU, f), gate)
        try { ops.uninstallPackage("com.example.app"); fail() } catch (_: PrivilegedOpException) {}
        assertTrue(f.calls.isEmpty())
        gate.approve(PowerAction.UNINSTALL, "com.example.app")
        ops.uninstallPackage("com.example.app")
        assertEquals(1, f.calls.size)
        try { ops.uninstallPackage("com.example.app"); fail() } catch (_: PrivilegedOpException) {}
        gate.approve(PowerAction.CHMOD, "/a")
        try { ops.chmod("/b", "644"); fail() } catch (_: PrivilegedOpException) {}
        ops.list("/data") // reads are not gated
    }
}

class PrivilegedOpsSelectorTest {
    private val root = UnprivilegedOps { "root" }
    private val shz = UnprivilegedOps { "shz" }
    private val none = UnprivilegedOps { "none" }
    private fun a(rootOn: Boolean, rootAv: Boolean, shOn: Boolean, shAv: Boolean, perm: Boolean) =
        PrivilegeAvailability(rootAv, rootOn, shAv, perm, shOn)

    @Test fun order() {
        assertSame(root, PrivilegedOpsSelector.select(a(true, true, true, true, true), { root }, { shz }, { none }))
        assertSame(shz, PrivilegedOpsSelector.select(a(false, true, true, true, true), { root }, { shz }, { none }))
        assertSame(none, PrivilegedOpsSelector.select(a(false, false, true, true, false), { root }, { shz }, { none }))
        assertSame(none, PrivilegedOpsSelector.select(a(false, false, false, false, false), { root }, { shz }, { none }))
    }

    @Test fun unboundServiceFallsBack() {
        assertSame(none, PrivilegedOpsSelector.select(a(false, false, true, true, true), { root }, { null }, { none }))
    }
}
