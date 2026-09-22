package eu.darken.porter.manager.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

/**
 * A one-shot wait for the user to unlock the device.
 *
 * At most one registration at a time, because the caller asks for the wait again on every further
 * `adb_wifi_enabled` write that lands while the keyguard is up. A second receiver would take the
 * first one's place with nothing left holding a reference to unregister it.
 *
 * [stop] is terminal. Three threads reach this: the settings callback that asks to wait, the
 * broadcast that ends the wait, and the owner's cleanup. A [start] already under way when cleanup
 * runs would otherwise register a receiver after the last thing that would have unregistered it,
 * leaving a dead wait that still answers an unlock.
 */
internal class UnlockWaiter(
    private val context: Context,
    private val onUnlock: () -> Unit,
) {

    private val lock = Any()

    private var receiver: BroadcastReceiver? = null

    private var closed = false

    /** Registers the wait, or reports false when one is already registered or this is over. */
    fun start(): Boolean {
        synchronized(lock) {
            if (closed || receiver != null) return false
            val created = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != Intent.ACTION_USER_PRESENT) return
                    // Whoever takes the registration down runs [onUnlock], so a repeated broadcast
                    // and a concurrent [stop] cannot both act on the same wait.
                    if (stop()) onUnlock()
                }
            }
            receiver = created
            context.registerReceiver(created, IntentFilter(Intent.ACTION_USER_PRESENT))
            return true
        }
    }

    /** Ends the wait for good, or reports false when there was none to end. */
    fun stop(): Boolean {
        val registered = synchronized(lock) {
            closed = true
            receiver?.also { receiver = null }
        } ?: return false
        runCatching { context.unregisterReceiver(registered) }
        return true
    }
}
