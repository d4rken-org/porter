package eu.darken.porter.manager.support

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.os.UserManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import eu.darken.porter.manager.BuildConfig
import eu.darken.porter.manager.R
import eu.darken.porter.manager.model.PorterServiceVersion
import eu.darken.porter.manager.utils.PorterStateMachine
import eu.darken.porter.sdk.Porter
import java.io.File

class DebugRecorder internal constructor(
    private val appContext: Context,
    storeOverride: DebugLogStore? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
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
    private var serverStream: ServerDiagnostics.ServerStream? = null
    private var serverReader: Job? = null
    private var serverWatcher: Job? = null
    private var debugLease: DebugLease? = null

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
                    if (process == null) store.activeId()?.let { resume(it) }
                    store.prune()
                }
            } catch (e: Exception) { report(e) }
        }
    }

    suspend fun start() = withContext(Dispatchers.IO + NonCancellable) {
        mutex.withLock {
            if (state.value.active) return@withLock
            val id = store.create()
            try { resume(id) }
            catch (e: Exception) {
                store.finish()
                report(e)
                throw e
            }
        }
    }

    private suspend fun resume(id: String) {
        val directory = store.directory(id)
        val started = id.substringBefore('-').toLong()
        val remaining = MAX_DURATION - (System.currentTimeMillis() - started).coerceAtLeast(0)
        if (!directory.isDirectory || remaining <= 0) {
            store.finish()
            return
        }
        File(directory, "device.txt").writeText(deviceDetails())
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                val exits = appContext.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(null, 0, 5)
                File(directory, "process-exits.txt").writeText(exits.joinToString("\n") {
                    "time=${it.timestamp} reason=${it.reason} status=${it.status} importance=${it.importance} description=${it.description}"
                })
            }
        }
        File(directory, "events.txt").appendText("Recording manager pid=${Process.myPid()} at ${System.currentTimeMillis()}\n")
        val child = ProcessBuilder("logcat", "-v", "threadtime", "--pid=${Process.myPid()}", "-T", "1")
            .redirectErrorStream(true).start()
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
            ServerDiagnostics.captureMetadata(directory, "start")
            attachServerStream(directory, remaining)
        } catch (e: Exception) {
            process = null
            child.destroy()
            runCatching { child.inputStream.close() }
            reader?.join()
            reader = null
            timer?.cancel()
            detachServerStream()
            store.finish()
            mutableState.value = State()
            appContext.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            throw e
        }
    }

    /**
     * Runs with [mutex] held. The coroutines it launches never take the lock and never touch these
     * fields, because [stop] joins them while holding it.
     */
    private fun attachServerStream(directory: File, remaining: Long) {
        val events = File(directory, "events.txt")
        serverWatcher = scope.launch {
            PorterStateMachine.instance.asFlow().collect {
                runCatching { events.appendText("Service $it at ${System.currentTimeMillis()}\n") }
            }
        }
        acquireDebugLease(events, remaining)
        val handle = ServerDiagnostics.openStream(directory)
        if (handle == null) {
            events.appendText("Server stream unavailable at ${System.currentTimeMillis()}\n")
            return
        }
        serverStream = handle
        events.appendText("Server stream attached pid=${handle.pid} at ${System.currentTimeMillis()}\n")
        serverReader = scope.launch { readServerStream(handle, directory, SERVER_MAX_LOG_BYTES) }
    }

    private class DebugLease(val binder: IBinder, val token: IBinder)

    /**
     * Runs with [mutex] held, like [attachServerStream]. Every outcome of the request is recorded,
     * because a recording that silently lacks service debug detail looks identical to one where
     * nothing happened. A recording without the lease is still worth having, so no outcome here
     * stops one.
     */
    private fun acquireDebugLease(events: File, remaining: Long) {
        fun note(what: String) = runCatching { events.appendText("$what at ${System.currentTimeMillis()}\n") }
        val binder = Porter.connection.value?.takeIf { it.isAlive }?.binder
        if (binder == null) {
            note("Debug logging unavailable")
            return
        }
        val token = Binder()
        val granted = try {
            ServerDiagnostics.requestDebugLogging(binder, token, remaining)
        } catch (e: SecurityException) {
            // The service answered and said no, which is a different thing from the call breaking.
            note("Debug logging refused")
            return
        } catch (e: Exception) {
            Log.w("PorterRecorder", "Debug logging request failed", e)
            note("Debug logging failed")
            return
        }
        when {
            granted == null -> note("Debug logging unsupported by this service")
            granted <= 0 -> note("Debug logging granted nothing")
            else -> {
                debugLease = DebugLease(binder, token)
                note("Debug logging granted for ${granted}ms")
                // The service clamps what it grants, so a long recording can outlive its own gate.
                if (granted < remaining) note("Debug logging expires ${remaining - granted}ms early")
            }
        }
    }

    /** Released against the binder that granted it, which a replaced service no longer answers. */
    private fun releaseDebugLease() {
        val lease = debugLease ?: return
        debugLease = null
        runCatching { ServerDiagnostics.requestDebugLogging(lease.binder, lease.token, 0) }
    }

    private suspend fun detachServerStream() {
        serverWatcher?.cancel()
        serverWatcher = null
        // Released before the stream closes: stop producing the detail first, then stop capturing
        // it. The other order leaves the service briefly logging what nothing is reading.
        releaseDebugLease()
        serverStream?.close()
        serverStream = null
        serverReader?.join()
        serverReader = null
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
            detachServerStream()
            val directory = store.directory(id)
            store.finish()
            mutableState.value = State()
            appContext.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            ServerDiagnostics.captureMetadata(directory, "stop")
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
        private const val SERVER_MAX_LOG_BYTES = 8L * 1024 * 1024
        private const val CHANNEL = "debug_recording"
        private const val NOTIFICATION_ID = 920

        fun deviceDetails() = """
            Porter ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})
            Installed build: ${PorterServiceVersion.installed.buildId}
            Device: ${Build.MANUFACTURER} ${Build.MODEL}
            Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
            Build: ${Build.DISPLAY}
            ABIs: ${Build.SUPPORTED_ABIS.joinToString()}
        """.trimIndent()

        @Volatile private var instance: DebugRecorder? = null
        fun get(context: Context): DebugRecorder = instance ?: synchronized(this) {
            instance ?: DebugRecorder(context.applicationContext).also { instance = it }
        }

        internal fun resetForTest() { instance = null }
    }
}
