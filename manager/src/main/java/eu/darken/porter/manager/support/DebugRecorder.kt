package eu.darken.porter.manager.support

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import eu.darken.porter.manager.BuildConfig
import eu.darken.porter.manager.R
import eu.darken.porter.manager.model.PorterServiceVersion
import eu.darken.porter.manager.utils.EnvironmentUtils
import eu.darken.porter.manager.utils.PorterStateMachine
import eu.darken.porter.manager.ServerBinder
import java.io.File

class DebugRecorder internal constructor(
    private val appContext: Context,
    storeOverride: DebugLogStore? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val managerLog: (pid: Int) -> java.lang.Process = { pid ->
        ProcessBuilder("logcat", "-v", "threadtime", "--pid=$pid", "-T", "1").redirectErrorStream(true).start()
    },
    private val binders: StateFlow<IBinder?> = ServerBinder.binder,
    private val anchors: ClockAnchors = ClockAnchors(appContext),
    private val serviceOperations: ServiceFollower.Operations = ServiceFollower.ServiceOperations(appContext, anchors),
    private val serviceStates: () -> Flow<PorterStateMachine.State> = { PorterStateMachine.instance.asFlow() },
    private val describeDevice: () -> String = { deviceDetails(appContext) },
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    data class State(val active: Boolean = false, val started: Long = 0, val error: String? = null)
    private val store: DebugLogStore by lazy {
        storeOverride ?: DebugLogStore(File(appContext.noBackupFilesDir, "debug-logs"))
    }
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    private var process: java.lang.Process? = null
    private var reader: Job? = null
    private var timer: Job? = null
    private var follower: ServiceFollower? = null
    private var serverWatcher: Job? = null

    fun attach() {
        val userManager = appContext.getSystemService(UserManager::class.java)
        if (!userManager.isUserUnlocked) {
            val handled = java.util.concurrent.atomic.AtomicBoolean(false)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (handled.compareAndSet(false, true)) {
                        context.unregisterReceiver(this)
                        attach()
                    }
                }
            }
            ContextCompat.registerReceiver(appContext, receiver, IntentFilter(Intent.ACTION_USER_UNLOCKED), ContextCompat.RECEIVER_NOT_EXPORTED)
            if (userManager.isUserUnlocked && handled.compareAndSet(false, true)) {
                appContext.unregisterReceiver(receiver)
                attach()
            }
            return
        }
        scope.launch {
            try {
                mutex.withLock {
                    if (process == null) store.activeId()?.let { resume(it, fresh = false) }
                    store.prune()
                }
            } catch (e: Exception) { report(e) }
        }
    }

    suspend fun start() = withContext(Dispatchers.IO + NonCancellable) {
        mutex.withLock {
            if (state.value.active) return@withLock
            val id = store.create()
            try { resume(id, fresh = true) }
            catch (e: Exception) {
                store.finish()
                report(e)
                throw e
            }
        }
    }

    /** [fresh] tells a new session from one continued by a manager process that came back. */
    private suspend fun resume(id: String, fresh: Boolean) {
        val directory = store.directory(id)
        val started = id.substringBefore('-').toLong()
        val remaining = MAX_DURATION - (System.currentTimeMillis() - started).coerceAtLeast(0)
        if (!directory.isDirectory || remaining <= 0) {
            store.finish()
            return
        }
        File(directory, "device.txt").writeText(describeDevice())
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                val exits = appContext.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(null, 0, 5)
                File(directory, "process-exits.txt").writeText(exits.joinToString("\n") {
                    "time=${it.timestamp} reason=${it.reason} status=${it.status} importance=${it.importance} description=${it.description}"
                })
            }
        }
        val deadline = anchors.elapsedNow() + remaining
        val events = File(directory, "events.txt")
        events.appendText("Recording manager pid=${Process.myPid()} at ${wallClock()}\n")
        anchors.append(events, if (fresh) "start" else "resume")
        val child = managerLog(Process.myPid())
        try {
            process = child
            mutableState.value = State(true, started)
            showNotification()
            reader = scope.launch {
                try {
                    child.inputStream.use { DebugLogStore.appendRotating(it, File(directory, "manager.log")) }
                } catch (e: Exception) {
                    Log.w("PorterRecorder", "Log stream ended", e)
                } finally {
                    child.destroy()
                }
                // End of stream completes the session without leaving a stale recording state.
                scope.launch { stop(child) }
            }
            timer = scope.launch { delay(remaining); scope.launch { stop(child) } }
            ServerDiagnostics.captureMetadata(appContext, directory, "start", anchors)
            follower = ServiceFollower(binders, serviceOperations, directory, deadline, anchors, scope).also { it.start() }
            watchServiceState(directory)
        } catch (e: Exception) {
            process = null
            child.destroy()
            runCatching { child.inputStream.close() }
            reader?.join()
            reader = null
            timer?.cancel()
            detachService()
            store.finish()
            mutableState.value = State()
            appContext.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            throw e
        }
    }

    /**
     * Runs with [mutex] held. The coroutine it launches never takes the lock and never touches
     * these fields, because [stop] joins it while holding it.
     */
    private fun watchServiceState(directory: File) {
        val events = File(directory, "events.txt")
        val states = serviceStates()
        serverWatcher = scope.launch {
            states.collect {
                runCatching { events.appendText("Service $it at ${wallClock()}\n") }
            }
        }
    }

    /** Runs with [mutex] held, like [watchServiceState]. */
    private suspend fun detachService() {
        serverWatcher?.cancelAndJoin()
        serverWatcher = null
        follower?.cancelAndJoin()
        follower = null
    }

    suspend fun stop() = stop(null)
    private suspend fun stop(expected: java.lang.Process?) = withContext(Dispatchers.IO + NonCancellable) {
        mutex.withLock {
            if (expected != null && process !== expected) return@withLock
            val id = store.activeId() ?: return@withLock
            val child = process
            process = null
            timer?.cancel()
            child?.destroy()
            // Closing the pipe unblocks logcat's reader before the session can be exported.
            runCatching { child?.inputStream?.close() }
            reader?.join()
            reader = null
            detachService()
            val directory = store.directory(id)
            anchors.append(File(directory, "events.txt"), "stop")
            store.finish()
            mutableState.value = State()
            appContext.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            ServerDiagnostics.captureMetadata(appContext, directory, "stop", anchors)
            store.prune()
        }
    }

    internal suspend fun sessions() = withContext(Dispatchers.IO) {
        mutex.withLock { store.sessions() }
    }
    internal suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            store.delete(id)
            File(appContext.cacheDir, "debug-exports/porter-$id.zip").delete()
        }
    }
    internal suspend fun export(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock { store.export(id, File(appContext.cacheDir, "debug-exports")) }
    }
    private fun report(e: Exception) {
        Log.e("PorterRecorder", "Recording failed", e)
        mutableState.value = State(error = e.localizedMessage ?: e.javaClass.simpleName)
    }

    private fun showNotification() {
        val manager = appContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(
            CHANNEL, appContext.getString(R.string.porter_debug_title), NotificationManager.IMPORTANCE_LOW,
        ))
        val pending = PendingIntent.getActivity(appContext, NOTIFICATION_ID,
            Intent(appContext, SupportActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getBroadcast(appContext, NOTIFICATION_ID,
            Intent(appContext, StopRecordingReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        runCatching {
            manager.notify(NOTIFICATION_ID, NotificationCompat.Builder(appContext, CHANNEL)
                .setSmallIcon(R.drawable.ic_outline_info_24).setContentTitle(appContext.getString(R.string.porter_debug_recording))
                .setContentText(appContext.getString(R.string.porter_debug_notification)).setContentIntent(pending)
                .setOngoing(true).addAction(0, appContext.getString(R.string.porter_debug_stop), stop).build())
        }
    }

    companion object {
        private const val MAX_DURATION = 30 * 60 * 1000L
        private const val CHANNEL = "debug_recording"
        private const val NOTIFICATION_ID = 920

        fun deviceDetails(context: Context): String =
            deviceDetails(context, EnvironmentUtils.getAdbTcpPort(), EnvironmentUtils.isTlsSupported())

        internal fun deviceDetails(context: Context, adbTcpPort: Int, tlsSupported: Boolean): String {
            val resolver = context.contentResolver
            val lines = mutableListOf(
                "Porter ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                "Installed build: ${PorterServiceVersion.installed.buildId}",
                "Device: ${Build.MANUFACTURER} ${Build.MODEL}",
                "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                "Build: ${Build.DISPLAY}",
                "ABIs: ${Build.SUPPORTED_ABIS.joinToString()}",
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) lines += "SDK_INT_FULL: ${Build.VERSION.SDK_INT_FULL}"
            val secureSettings = context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
            lines += "WRITE_SECURE_SETTINGS: ${if (secureSettings) "granted" else "denied"}"
            // -1 means the setting is absent.
            for (name in listOf(Settings.Global.ADB_ENABLED, "adb_wifi_enabled", Settings.Global.DEVELOPMENT_SETTINGS_ENABLED)) {
                lines += "$name: ${Settings.Global.getInt(resolver, name, -1)}"
            }
            lines += "ADB TCP port: $adbTcpPort"
            lines += "TLS supported: $tlsSupported"
            return lines.joinToString("\n")
        }

        @Volatile private var instance: DebugRecorder? = null
        fun get(context: Context): DebugRecorder = instance ?: synchronized(this) {
            instance ?: DebugRecorder(context.applicationContext).also { instance = it }
        }

        internal fun resetForTest() { instance = null }
    }
}
