package moe.shizuku.manager.starter

import android.content.Context
import android.os.DeadObjectException
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import moe.shizuku.manager.ShizukuApplication
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.model.PorterServiceVersion
import moe.shizuku.manager.support.ServerDiagnostics
import moe.shizuku.manager.utils.ShizukuStateMachine
import moe.shizuku.manager.utils.UserHandleCompat
import moe.shizuku.server.IShizukuService
import moe.shizuku.server.IRemoteProcess
import rikka.shizuku.Shizuku
import java.io.File

internal class ReplacementLaunchUncertain : IllegalStateException("Service update launcher did not finish in time; its outcome is unknown")

internal suspend fun awaitReplacementStarter(process: IRemoteProcess) {
    val code = withTimeoutOrNull(10_000) {
        while (process.alive()) delay(25)
        process.exitValue()
    } ?: throw ReplacementLaunchUncertain()
    check(code == 0) { "Service update preflight failed (exit $code); the running service was not replaced" }
}

internal class ServiceReplacer(
    private val installed: PorterServiceVersion,
    private val currentBinder: () -> IBinder?,
    private val readInfo: (IBinder) -> ServerDiagnostics.Info? = ServerDiagnostics::readInfo,
    private val readUid: (IBinder) -> Int = { IShizukuService.Stub.asInterface(it).uid },
    private val isReady: () -> Boolean = { true },
    private val launch: suspend (IBinder, Int) -> Unit,
) {
    suspend fun replace(onLaunched: () -> Unit = {}, beforeLaunch: () -> Unit) {
        val old = currentBinder() ?: error("The Porter service is no longer running")
        val previous = readInfo(old) ?: error("The running service cannot identify its Porter process")
        if (previous.version?.matches(installed) == true) return
        val uid = readUid(old)
        check(uid == 0 || uid == 2000) { "The running service has no shell or root privileges" }
        currentCoroutineContext().ensureActive()
        beforeLaunch()
        // Once the starter can kill the old service, finish the bounded handoff even if its worker is cancelled.
        withContext(NonCancellable) {
            try {
                launch(old, previous.pid)
            } catch (_: DeadObjectException) {
                // The detached starter can stop the old service before newProcess returns its reply.
            } catch (e: ReplacementLaunchUncertain) {
                // A slow starter can still replace the service. Keep the watchdog masked during the handoff window.
                moe.shizuku.manager.utils.Logger.LOGGER.w(e, "Waiting for uncertain service update launch")
            }
            onLaunched()
            withTimeout(60_000) {
                while (true) {
                    val next = currentBinder()
                    if (next != null && next != old && next.pingBinder() && isReady()) {
                        val info = readInfo(next)
                        if (info != null && info.pid != previous.pid) {
                            check(info.version?.matches(installed) == true) { "The replacement service has a different build" }
                            check(readUid(next) == uid) { "The replacement service has different privileges" }
                            return@withTimeout
                        }
                    }
                    delay(100)
                }
            }
        }
    }
}

internal object ServiceReplacement {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val controller by lazy {
        val app = ShizukuApplication.application
        val preferences = app.createDeviceProtectedStorageContext().getSharedPreferences("service-update", Context.MODE_PRIVATE)
        ServiceUpdateController(ServiceUpdateStore(preferences, PorterServiceVersion.installed.buildId!!),
            isPrimaryUser = { UserHandleCompat.myUserId() == 0 }, automaticEnabled = ShizukuSettings::getAutoUpdateService,
            replace = { beforeLaunch, onLaunched ->
                withContext(Dispatchers.IO) {
                    val apk = File(app.applicationInfo.sourceDir)
                    val starter = File(app.applicationInfo.nativeLibraryDir, "libshizuku.so")
                    check(apk.canRead() && starter.canExecute()) { "The installed Porter starter is unavailable" }
                    ServiceReplacer(PorterServiceVersion.installed, Shizuku::getBinder,
                        isReady = ShizukuStateMachine::isRunning, launch = { binder, pid ->
                            val process = IShizukuService.Stub.asInterface(binder).newProcess(
                                arrayOf(starter.absolutePath, "--apk=${apk.absolutePath}", "--replace=$pid"), null, null)
                            awaitReplacementStarter(process)
                        }).replace(onLaunched) {
                            beforeLaunch()
                            ShizukuStateMachine.set(ShizukuStateMachine.State.STARTING)
                        }
                }
            }, onFinished = { ShizukuStateMachine.update() })
    }
    val state get() = controller.state

    fun startManual() { scope.launch { controller.update(automatic = false) } }
    suspend fun updateInBackground() = controller.update(automatic = true)
    fun reconcile() {
        scope.launch {
            controller.reconcile {
                withContext(Dispatchers.IO) {
                    runCatching { Shizuku.getBinder()?.let { ServerDiagnostics.readInfo(it)?.version?.matches(PorterServiceVersion.installed) } == true }.getOrDefault(false)
                }
            }
        }
    }
}
