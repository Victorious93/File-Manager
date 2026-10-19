package ai.victorious.victorfiles.priv

import ai.victorious.victorfiles.BuildConfig
import ai.victorious.victorfiles.core.priv.CommandRunner
import ai.victorious.victorfiles.core.priv.PrivilegeBackend
import ai.victorious.victorfiles.core.priv.PrivilegedOps
import ai.victorious.victorfiles.core.priv.ShellPrivilegedOps
import ai.victorious.victorfiles.core.priv.ShizukuProbe
import ai.victorious.victorfiles.core.priv.ShizukuState
import ai.victorious.victorfiles.core.priv.ShizukuStateMachine
import ai.victorious.victorfiles.core.shell.ShellResult
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import rikka.sui.Sui

/**
 * Single owner of the Shizuku/Sui API: detection, permission request + listener, and the
 * privileged UserService binding. Everything else talks to [state] and [ops].
 */
class ShizukuController(private val context: Context) {
    private val _state = MutableStateFlow(ShizukuState.NOT_INSTALLED)
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    @Volatile private var service: IPrivilegedService? = null
    @Volatile private var bindLatch = CountDownLatch(1)

    val isSui: Boolean = runCatching { Sui.init(context.packageName) }.getOrDefault(false)

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener {
        service = null
        _state.value = ShizukuStateMachine.onBinderDead(_state.value)
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == REQUEST_CODE) {
            _state.value = ShizukuStateMachine.onPermissionResult(_state.value, result == PackageManager.PERMISSION_GRANTED)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) service = IPrivilegedService.Stub.asInterface(binder)
            bindLatch.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(context.packageName, PrivilegedUserService::class.java.name))
            .daemon(false)
            .processNameSuffix("priv")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    fun start() {
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(binderReceived)
            Shizuku.addBinderDeadListener(binderDead)
            Shizuku.addRequestPermissionResultListener(permissionResult)
        }
        refresh()
    }

    fun refresh(): ShizukuState {
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val preV11 = alive && runCatching { Shizuku.isPreV11() }.getOrDefault(false)
        val granted = alive && !preV11 &&
            runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        val installed = isSui || runCatching {
            context.packageManager.getPackageInfo(MANAGER_PACKAGE, 0); true
        }.getOrDefault(false)
        return ShizukuStateMachine.resolve(ShizukuProbe(installed, alive, granted, preV11)).also { _state.value = it }
    }

    /** Shows the Shizuku permission dialog. Only call from an explicit user tap. */
    fun requestPermission(): Boolean {
        if (!ShizukuStateMachine.canRequestPermission(refresh())) return false
        return runCatching { Shizuku.requestPermission(REQUEST_CODE); true }.getOrDefault(false)
    }

    /** Blocking bind (call off the main thread). Returns null when not GRANTED or bind fails. */
    fun awaitService(timeoutMs: Long = 5000): IPrivilegedService? {
        service?.let { if (it.asBinder().pingBinder()) return it }
        if (refresh() != ShizukuState.GRANTED) return null
        val latch = CountDownLatch(1).also { bindLatch = it }
        runCatching { Shizuku.bindUserService(args, connection) }.onFailure { return null }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return service
    }

    fun unbind() {
        runCatching { Shizuku.unbindUserService(args, connection, true) }
        service = null
    }

    /** [PrivilegedOps] backed by the UserService, or null if it cannot be bound right now. */
    fun ops(): PrivilegedOps? {
        if (_state.value != ShizukuState.GRANTED && refresh() != ShizukuState.GRANTED) return null
        return ShellPrivilegedOps(PrivilegeBackend.SHIZUKU, runner)
    }

    val runner = CommandRunner { command, stdin -> exec(command, stdin, 60) }

    fun exec(command: String, stdin: String? = null, timeoutSec: Long = 60): ShellResult {
        val s = awaitService() ?: return ShellResult(126, "", "Shizuku service unavailable")
        return try {
            val r = s.exec(command, stdin, timeoutSec)
            ShellResult(r.getOrNull(0)?.toIntOrNull() ?: 1, r.getOrElse(1) { "" }, r.getOrElse(2) { "" })
        } catch (e: Exception) {
            service = null
            ShellResult(1, "", "Shizuku service call failed")
        }
    }

    companion object {
        const val REQUEST_CODE = 9001
        const val MANAGER_PACKAGE = "moe.shizuku.privileged.api"
    }
}
