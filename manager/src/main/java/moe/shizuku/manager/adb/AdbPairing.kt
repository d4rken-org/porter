package moe.shizuku.manager.adb

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import java.net.ConnectException
import java.net.SocketTimeoutException

internal suspend fun pairAdb(host: String, port: Int, code: String): Unit = suspendCancellableCoroutine { continuation ->
    val activeClient = AtomicReference<AdbPairingClient?>()
    continuation.invokeOnCancellation { activeClient.get()?.cancel() }
    Dispatchers.IO.dispatch(continuation.context) {
        try {
            if (!continuation.isActive) return@dispatch
            val key = try {
                AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()))
            } catch (e: Exception) {
                throw AdbKeyException(e)
            }
            AdbPairingClient(host, port, code, key).use { client ->
                activeClient.set(client)
                if (!continuation.isActive) return@dispatch
                if (!client.start()) throw AdbInvalidPairingCodeException()
            }
            continuation.resume(Unit)
        } catch (e: Throwable) {
            continuation.resumeWithException(e as? Exception ?: RuntimeException("Pairing failed", e))
        } finally {
            activeClient.set(null)
        }
    }
}

internal fun Context.pairingFailureMessage(error: Throwable): String = getString(when (error) {
    is AdbInvalidPairingCodeException -> R.string.paring_code_is_wrong
    is AdbKeyException -> R.string.adb_error_key_store
    is ConnectException -> R.string.cannot_connect_port
    is SocketTimeoutException -> R.string.porter_pairing_attempt_timeout
    else -> R.string.porter_pairing_failed_retry
})
