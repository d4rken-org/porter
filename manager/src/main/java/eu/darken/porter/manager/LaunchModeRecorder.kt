package eu.darken.porter.manager

import android.os.IBinder
import androidx.annotation.VisibleForTesting
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import eu.darken.porter.manager.utils.LOGGER
import eu.darken.porter.server.IPorterService

/**
 * Remembers how the server that is running now was started, which is what
 * [eu.darken.porter.manager.receiver.PorterReceiverStarter] repeats after a boot.
 *
 * The privilege the server actually has decides it, not the button that was pressed: an attempt
 * that fails before the server exists must not overwrite a mode that works, and a server someone
 * started from a computer's adb shell is worth recording too.
 */
object LaunchModeRecorder {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val attached = AtomicBoolean(false)

    /**
     * Starts following the delivered server binder. Explicit and once-only, like
     * [eu.darken.porter.manager.utils.PorterStateMachine.attachToPorter]: a second collector would
     * record every delivery twice.
     */
    fun attachToPorter() {
        if (!attached.compareAndSet(false, true)) return
        scope.launch {
            ServerBinder.binder.collect { binder -> if (binder != null) record(binder) }
        }
    }

    /**
     * Deliberately not gated on [eu.darken.porter.manager.utils.PorterStateMachine] being RUNNING.
     * That state is set from a separate Main-dispatched collector of the same binder, so this one
     * can resolve the uid first, find the state still STOPPED, and skip a write that no later
     * delivery would come back to redo.
     */
    @VisibleForTesting
    internal fun record(binder: IBinder) {
        val uid = try {
            IPorterService.Stub.asInterface(binder).uid
        } catch (e: Exception) {
            LOGGER.w(e, "Read the uid of the running server")
            return
        }
        if (ServerBinder.binder.value !== binder) return

        val method = if (uid == 0) PorterSettings.LaunchMethod.ROOT else PorterSettings.LaunchMethod.ADB
        if (PorterSettings.lastLaunchMode != method) PorterSettings.lastLaunchMode = method
    }
}
