package eu.darken.porter.manager.support

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Gives every service instance delivered during a recording its own log stream and debug lease.
 * Each attach works against the one binder it was handed, never against whatever is current by
 * then, so a replacement arriving midway cannot receive half of another instance's setup.
 *
 * Nothing here takes the recorder's lock, and nothing may escape: the recorder's scope has no
 * exception handler.
 */
internal class ServiceFollower(
    private val binders: Flow<IBinder?>,
    private val operations: Operations,
    private val directory: File,
    private val deadlineElapsed: Long,
    private val elapsedNow: () -> Long = SystemClock::elapsedRealtime,
    private val scope: CoroutineScope,
) {
    interface Operations {
        fun readInfo(binder: IBinder): ServerDiagnostics.Info?
        fun requestLease(binder: IBinder, token: IBinder, durationMs: Long): Long?
        fun releaseLease(binder: IBinder, token: IBinder)
        fun openStream(binder: IBinder, pid: Int, directory: File, since: String): ServerDiagnostics.ServerStream?
        suspend fun captureMetadata(binder: IBinder, file: File)
    }

    class ServiceOperations(private val context: Context) : Operations {
        override fun readInfo(binder: IBinder) = ServerDiagnostics.readInfo(binder)
        override fun requestLease(binder: IBinder, token: IBinder, durationMs: Long) =
            ServerDiagnostics.requestDebugLogging(binder, token, durationMs)
        override fun releaseLease(binder: IBinder, token: IBinder) {
            ServerDiagnostics.requestDebugLogging(binder, token, 0)
        }
        override fun openStream(binder: IBinder, pid: Int, directory: File, since: String) =
            ServerDiagnostics.openStream(binder, pid, directory, since)
        override suspend fun captureMetadata(binder: IBinder, file: File) =
            ServerDiagnostics.captureMetadata(context, binder, file)
    }

    /** What one attach has acquired so far, so a cancelled attach releases exactly that. */
    private class Instance(val binder: IBinder) {
        var lease: IBinder? = null
        var stream: ServerDiagnostics.ServerStream? = null
        var reader: Job? = null
    }

    private val events = File(directory, "events.txt")
    private var job: Job? = null
    private var current: Instance? = null

    fun start() {
        check(job == null)
        job = scope.launch {
            try {
                follow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Following the service failed", e)
                note("Service following failed: ${e.javaClass.simpleName}")
            } finally {
                detach()
            }
        }
    }

    suspend fun cancelAndJoin() {
        job?.cancelAndJoin()
    }

    private suspend fun follow() {
        var attaches = highestAttach()
        var first = true
        var previousNull = false
        var attachedBefore = false
        var nullSinceAttach = false
        binders.collect { binder ->
            val isFirst = first
            first = false
            if (binder == null) {
                when {
                    isFirst -> note("Waiting for Porter service")
                    !previousNull -> {
                        detach()
                        note("Service binder lost")
                    }
                }
                previousNull = true
                nullSinceAttach = true
                return@collect
            }
            previousNull = false
            if (binder === current?.binder) return@collect
            detach()
            if (!pings(binder)) {
                note("Service binder not answering")
                return@collect
            }
            if (attachedBefore && !nullSinceAttach) note("Service replaced")
            attachedBefore = true
            nullSinceAttach = false
            attach(binder, ++attaches)
        }
    }

    private suspend fun attach(binder: IBinder, attach: Int) {
        val instance = Instance(binder).also { current = it }
        val pid = try {
            operations.readInfo(binder)?.pid
        } catch (e: Exception) {
            note("Service diagnostics failed: ${e.javaClass.simpleName}")
            null
        }
        note("Service binder arrived attach=$attach pid=${pid ?: "unknown"}")
        val metadata = File(directory, "server-attach-$attach-${if (pid != null) "pid$pid" else "unknown"}.txt")
        try {
            operations.captureMetadata(binder, metadata)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            note("Service metadata failed: ${e.javaClass.simpleName}")
        }
        acquireLease(instance)
        currentCoroutineContext().ensureActive()
        val handle = pid?.let {
            try {
                // -T 1 follows without replaying the retained backlog, which -t would charge against the cap.
                operations.openStream(binder, it, directory, "1")
            } catch (e: Exception) {
                Log.w(TAG, "Opening the server stream failed", e)
                null
            }
        }
        if (handle == null) {
            note("Server stream unavailable")
            return
        }
        instance.stream = handle
        // A cancel that landed while the stream was opening is honoured before a reader exists.
        currentCoroutineContext().ensureActive()
        note("Server stream attached pid=$pid attach=$attach")
        instance.reader = scope.launch { readServerStream(handle, directory, SERVER_MAX_LOG_BYTES) }
    }

    /**
     * Every outcome of the request is recorded, because a recording that silently lacks service
     * debug detail looks identical to one where nothing happened. A recording without the lease is
     * still worth having, so no outcome here stops one.
     */
    private fun acquireLease(instance: Instance) {
        val remaining = deadlineElapsed - elapsedNow()
        if (remaining <= 0) {
            note("Debug logging skipped: recording ends at deadline")
            return
        }
        if (!pings(instance.binder)) {
            note("Debug logging unavailable")
            return
        }
        val token = Binder()
        val granted = try {
            operations.requestLease(instance.binder, token, remaining)
        } catch (e: SecurityException) {
            // The service answered and said no, which is a different thing from the call breaking.
            note("Debug logging refused")
            return
        } catch (e: Exception) {
            Log.w(TAG, "Debug logging request failed", e)
            note("Debug logging failed")
            return
        }
        when {
            granted == null -> note("Debug logging unsupported by this service")
            granted <= 0 -> note("Debug logging granted nothing")
            else -> {
                instance.lease = token
                note("Debug logging granted for ${granted}ms")
                // The service clamps what it grants, so a long recording can outlive its own gate.
                if (granted < remaining) note("Debug logging expires ${remaining - granted}ms early")
            }
        }
    }

    private suspend fun detach(): Unit = withContext(NonCancellable) {
        val instance = current ?: return@withContext
        current = null
        // Released before the stream closes: stop producing the detail first, then stop capturing
        // it. The other order leaves the service briefly logging what nothing is reading. The
        // release goes to the binder that granted it, which a replaced service no longer answers.
        instance.lease?.let { token -> runCatching { operations.releaseLease(instance.binder, token) } }
        instance.stream?.let { runCatching { it.close() } }
        instance.reader?.join()
    }

    /** Continues past the attaches of an earlier process recording into the same session. */
    private fun highestAttach(): Int = runCatching {
        directory.listFiles().orEmpty().mapNotNull { ATTACH_FILE.matchEntire(it.name)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull()
    }.getOrNull() ?: 0

    private fun pings(binder: IBinder) = runCatching { binder.pingBinder() }.getOrDefault(false)

    private fun note(what: String) {
        runCatching { events.appendText("$what at ${System.currentTimeMillis()}\n") }
    }

    companion object {
        private const val TAG = "PorterRecorder"
        private const val SERVER_MAX_LOG_BYTES = 8L * 1024 * 1024
        private val ATTACH_FILE = Regex("server-attach-(\\d+)-.*")
    }
}
