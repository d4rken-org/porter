package eu.darken.porter.manager.receiver

import android.content.Context
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.annotation.VisibleForTesting
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import eu.darken.porter.manager.AppConstants
import eu.darken.porter.manager.PorterSettings
import eu.darken.porter.manager.ServerBinder
import eu.darken.porter.manager.worker.AdbStartWorker

/**
 * Decides whether a BOOT_COMPLETED is a boot to start the server for. From Android 15 the system
 * sends it again whenever the app leaves the stopped state, which a running server causes by
 * handing its binder to a force-stopped manager. A fresh process cannot tell that server apart from
 * no server until the binder arrives, so the boot itself is what is remembered: the first delivery
 * of a boot claims it, and so does a server seen during it.
 */
object BootStart {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val attached = AtomicBoolean(false)

    /**
     * Marks every boot in which a server is delivered as needing no start, and ends a start that
     * is still waiting for its turn. Once-only.
     */
    fun attachToPorter(context: Context) {
        if (!attached.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        scope.launch { follow(appContext, ServerBinder.binder) }
    }

    @VisibleForTesting
    internal suspend fun follow(context: Context, binders: Flow<IBinder?>) {
        binders.collect { binder ->
            if (binder != null) {
                serverSeen(context)
                AdbStartWorker.supersede(context)
            }
        }
    }

    @VisibleForTesting
    internal fun serverSeen(context: Context) {
        bootCount(context)?.let { PorterSettings.claimBoot(it) }
    }

    /**
     * Whether this boot still needs its start, claiming it if so. A device that does not report a
     * boot count starts on every delivery.
     */
    fun claim(context: Context): Boolean {
        val boot = bootCount(context) ?: return true
        if (PorterSettings.claimBoot(boot)) return true
        Log.i(AppConstants.TAG, "Boot $boot needs no start; ignoring BOOT_COMPLETED")
        return false
    }

    private fun bootCount(context: Context): Int? = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
    } catch (e: Settings.SettingNotFoundException) {
        null
    }
}
