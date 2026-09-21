package eu.darken.porter.manager.starter

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
import eu.darken.porter.manager.PorterSettings
import eu.darken.porter.manager.ServerBinder
import eu.darken.porter.manager.model.PorterServiceVersion
import eu.darken.porter.manager.support.ServerDiagnostics
import eu.darken.porter.manager.utils.PorterStateMachine
import eu.darken.porter.manager.utils.UserHandleCompat
import eu.darken.porter.server.IPorterRemoteProcess
import eu.darken.porter.server.IPorterService
import java.io.File

internal class ReplacementLaunchUncertain : IllegalStateException("Service update launcher did not finish in time; its outcome is unknown")

internal suspend fun awaitReplacementStarter(process: IPorterRemoteProcess) {
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
    private val readUid: (IBinder) -> Int = { IPorterService.Stub.asInterface(it).uid },
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
                eu.darken.porter.manager.utils.LOGGER.w(e, "Waiting for uncertain service update launch")
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

private suspend fun launchReplacementStarter(binder: IBinder, previousPid: Int, apk: File, starter: File) {
    val process = ServerBinder.managerOf(binder).newProcess(
        arrayOf(starter.absolutePath, "--apk=${apk.absolutePath}", "--replace=$previousPid"), null, null)
    awaitReplacementStarter(process)
}

internal class ServiceReplacement internal constructor(
    private val appContext: Context,
    private val currentBinder: () -> IBinder? = { ServerBinder.binder.value },
    private val readInfo: (IBinder) -> ServerDiagnostics.Info? = ServerDiagnostics::readInfo,
    private val readUid: (IBinder) -> Int = { IPorterService.Stub.asInterface(it).uid },
    private val launch: suspend (IBinder, Int, File, File) -> Unit = ::launchReplacementStarter,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val controller = run {
        val preferences = appContext.createDeviceProtectedStorageContext().getSharedPreferences("service-update", Context.MODE_PRIVATE)
        ServiceUpdateController(ServiceUpdateStore(preferences, PorterServiceVersion.installed.buildId!!),
            isPrimaryUser = { UserHandleCompat.myUserId() == 0 }, automaticEnabled = PorterSettings::autoUpdateService,
            replace = { beforeLaunch, onLaunched ->
                withContext(Dispatchers.IO) {
                    val apk = File(appContext.applicationInfo.sourceDir)
                    val starter = File(appContext.applicationInfo.nativeLibraryDir, "libporter.so")
                    check(apk.canRead() && starter.canExecute()) { "The installed Porter starter is unavailable" }
                    ServiceReplacer(PorterServiceVersion.installed, currentBinder,
                        readInfo = readInfo, readUid = readUid,
                        isReady = PorterStateMachine.instance::isRunning, launch = { binder, pid ->
                            this@ServiceReplacement.launch(binder, pid, apk, starter)
                        }).replace(onLaunched) {
                            beforeLaunch()
                            PorterStateMachine.instance.set(PorterStateMachine.State.STARTING)
                        }
                }
            }, onFinished = { PorterStateMachine.instance.update() })
    }
    val state get() = controller.state

    fun startManual() { scope.launch { controller.update(automatic = false) } }
    suspend fun updateInBackground() = controller.update(automatic = true)
    fun reconcile() {
        scope.launch {
            controller.reconcile {
                withContext(Dispatchers.IO) {
                    runCatching { ServerBinder.binder.value?.let { ServerDiagnostics.readInfo(it)?.version?.matches(PorterServiceVersion.installed) } == true }.getOrDefault(false)
                }
            }
        }
    }

    companion object {
        @Volatile private var instance: ServiceReplacement? = null
        fun get(context: Context): ServiceReplacement = instance ?: synchronized(this) {
            instance ?: ServiceReplacement(context.applicationContext).also { instance = it }
        }

        internal fun resetForTest() { instance = null }
    }
}
