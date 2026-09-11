package moe.shizuku.manager.support

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.os.UserManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.R
import moe.shizuku.manager.model.PorterServiceVersion
import java.io.File

object DebugRecorder {
    data class State(val active: Boolean = false, val started: Long = 0, val error: String? = null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    private var process: java.lang.Process? = null
    private var reader: Job? = null
    private var timer: Job? = null
    private fun store(context: Context) = DebugLogStore(File(context.noBackupFilesDir, "debug-logs"))

    fun initialize(context: Context) {
        val userManager = context.getSystemService(UserManager::class.java)
        if (!userManager.isUserUnlocked) {
            val handled = java.util.concurrent.atomic.AtomicBoolean(false)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (handled.compareAndSet(false, true)) {
                        context.unregisterReceiver(this)
                        initialize(context)
                    }
                }
            }
            ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_USER_UNLOCKED), ContextCompat.RECEIVER_NOT_EXPORTED)
            if (userManager.isUserUnlocked && handled.compareAndSet(false, true)) {
                context.unregisterReceiver(receiver)
                initialize(context)
            }
            return
        }
        scope.launch {
            try {
                mutex.withLock {
                    val store = store(context)
                    if (process == null) store.activeId()?.let { resume(context, store, it) }
                    store.prune()
                }
            } catch (e: Exception) { report(e) }
        }
    }

    suspend fun start(context: Context) = withContext(Dispatchers.IO + NonCancellable) {
        mutex.withLock {
            if (state.value.active) return@withLock
            val store = store(context)
            val id = store.create()
            try { resume(context, store, id) }
            catch (e: Exception) {
                store.finish()
                report(e)
                throw e
            }
        }
    }

    private suspend fun resume(context: Context, store: DebugLogStore, id: String) {
        val directory = store.directory(id)
        val started = id.substringBefore('-').toLong()
        val remaining = MAX_DURATION - (System.currentTimeMillis() - started).coerceAtLeast(0)
        if (!directory.isDirectory || remaining <= 0 || File(directory, "manager.log").length() >= DebugLogStore.MAX_LOG_BYTES) {
            store.finish()
            return
        }
        File(directory, "device.txt").writeText(deviceDetails())
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                val exits = context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(null, 0, 5)
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
            showNotification(context)
            reader = scope.launch {
                try {
                    child.inputStream.use { DebugLogStore.appendBounded(it, File(directory, "manager.log")) }
                } catch (e: Exception) {
                    Log.w("PorterRecorder", "Log stream ended", e)
                } finally {
                    child.destroy()
                }
                // End-of-stream or size limit completes the session without leaving a stale recording state.
                scope.launch { stop(context, child) }
            }
            timer = scope.launch { delay(remaining); scope.launch { stop(context, child) } }
            ServerDiagnostics.capture(directory, "start")
        } catch (e: Exception) {
            process = null
            child.destroy()
            runCatching { child.inputStream.close() }
            reader?.join()
            reader = null
            timer?.cancel()
            store.finish()
            mutableState.value = State()
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            throw e
        }
    }

    suspend fun stop(context: Context) = stop(context, null)
    private suspend fun stop(context: Context, expected: java.lang.Process?) = withContext(Dispatchers.IO + NonCancellable) {
        mutex.withLock {
            if (expected != null && process !== expected) return@withLock
            val store = store(context)
            val id = store.activeId() ?: return@withLock
            val child = process
            process = null
            timer?.cancel()
            child?.destroy()
            // Closing the pipe unblocks logcat's reader before the session can be exported.
            runCatching { child?.inputStream?.close() }
            reader?.join()
            reader = null
            val directory = store.directory(id)
            store.finish()
            mutableState.value = State()
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            ServerDiagnostics.capture(directory, "stop")
            store.prune()
        }
    }

    internal suspend fun sessions(context: Context) = withContext(Dispatchers.IO) {
        mutex.withLock { store(context).sessions() }
    }
    internal suspend fun delete(context: Context, id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            store(context).delete(id)
            File(context.cacheDir, "debug-exports/porter-$id.zip").delete()
        }
    }
    internal suspend fun export(context: Context, id: String) = withContext(Dispatchers.IO) {
        mutex.withLock { store(context).export(id, File(context.cacheDir, "debug-exports")) }
    }
    private fun report(e: Exception) {
        Log.e("PorterRecorder", "Recording failed", e)
        mutableState.value = State(error = e.localizedMessage ?: e.javaClass.simpleName)
    }
    fun deviceDetails() = """
        Porter ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})
        Installed build: ${PorterServiceVersion.installed.buildId}
        Device: ${Build.MANUFACTURER} ${Build.MODEL}
        Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
        Build: ${Build.DISPLAY}
        ABIs: ${Build.SUPPORTED_ABIS.joinToString()}
    """.trimIndent()

    private fun showNotification(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(
            CHANNEL, context.getString(R.string.porter_debug_title), NotificationManager.IMPORTANCE_LOW,
        ))
        val pending = PendingIntent.getActivity(context, NOTIFICATION_ID,
            Intent(context, SupportActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getBroadcast(context, NOTIFICATION_ID,
            Intent(context, StopRecordingReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        runCatching {
            manager.notify(NOTIFICATION_ID, NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_outline_info_24).setContentTitle(context.getString(R.string.porter_debug_recording))
                .setContentText(context.getString(R.string.porter_debug_notification)).setContentIntent(pending)
                .setOngoing(true).addAction(0, context.getString(R.string.porter_debug_stop), stop).build())
        }
    }
    private const val MAX_DURATION = 30 * 60 * 1000L
    private const val CHANNEL = "debug_recording"
    private const val NOTIFICATION_ID = 920
}
