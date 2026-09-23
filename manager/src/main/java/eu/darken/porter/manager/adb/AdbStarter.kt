package eu.darken.porter.manager.adb

import android.content.Context
import android.util.Log
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import eu.darken.porter.manager.PorterSettings
import eu.darken.porter.manager.starter.Starter
import eu.darken.porter.manager.utils.PorterStateMachine

private const val TAG = "AdbStarter"

object AdbStarter {
    suspend fun startAdb(context: Context, port: Int, log: ((String) -> Unit)? = null) {
        suspend fun AdbClient.runCommand(cmd: String) {
            command(cmd) { log?.invoke(String(it)) }
        }

        PorterStateMachine.instance.set(PorterStateMachine.State.STARTING)
        log?.invoke("Starting with wireless adb...\n")

        withContext(Dispatchers.IO) {
            val key = runCatching { AdbKey(PreferenceAdbKeyStore(PorterSettings.preferences)) }
                .getOrElse {
                    if (it is CancellationException) throw it
                    else throw AdbKeyException(it)
                }

            log?.invoke("Connecting on port $port...")

            connectWithRetry(port, key).use { client ->
                log?.invoke("Successfully connected on port $port...\n")
                client.runCommand("shell:${Starter.internalCommand}")
            }
        }
    }

    private suspend fun connectWithRetry(port: Int, key: AdbKey): AdbClient {
        var delayTime = 0L
        val maxAttempts = 5
        for (attempt in 1..maxAttempts) {
            delay(delayTime)
            val client = AdbClient("127.0.0.1", port, key)
            try {
                client.connect()
                return client
            } catch (e: Exception) {
                client.close()
                Log.w(TAG, "Connection attempt $attempt of $maxAttempts failed", e)
                if (attempt == maxAttempts || e is SocketTimeoutException || e is AdbPairingRequiredException) throw e
                delayTime += 1000
            }
        }
        error("unreachable")
    }
}
