package moe.shizuku.manager.support

import android.util.Log
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.InputStream

/**
 * The recorder's scope has no exception handler, so nothing here may escape: an exception thrown
 * from the finally would not be caught by the catch above it and would take the process down.
 */
internal suspend fun readServerStream(handle: ServerDiagnostics.ServerStream, directory: File, limit: Long) {
    try {
        DebugLogStore.appendRotating(handle.input, File(directory, "server.log"), limit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("PorterRecorder", "Server stream ended", e)
    } finally {
        runCatching { handle.close() }
        runCatching {
            File(directory, "events.txt").appendText("Server stream ended pid=${handle.pid} at ${System.currentTimeMillis()}\n")
        }
    }
}

internal suspend fun drainServerErrors(error: InputStream, file: File, limit: Long) {
    try {
        DebugLogStore.drainBounded(error, file, limit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("PorterRecorder", "Server stream errors ended", e)
    }
}
