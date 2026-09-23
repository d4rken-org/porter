package eu.darken.porter.manager.worker

import android.content.Context
import android.os.IBinder
import androidx.annotation.VisibleForTesting
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import eu.darken.porter.manager.PorterSettings
import eu.darken.porter.manager.ServerBinder
import eu.darken.porter.manager.model.PorterServiceVersion
import eu.darken.porter.manager.support.ServerDiagnostics
import eu.darken.porter.manager.utils.LOGGER
import eu.darken.porter.manager.utils.UserHandleCompat

/**
 * Offers the automatic service update when a server of another build connects. An app update
 * brings that connection about whether or not MY_PACKAGE_REPLACED reaches the app.
 */
object ServiceUpdateTrigger {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val attached = AtomicBoolean(false)

    @VisibleForTesting
    internal var schedule: (Context) -> Unit = ServiceUpdateWorker::schedule

    /** A server that cannot report its build counts as another build, as on the Service screen. */
    @VisibleForTesting
    internal var runsInstalledBuild: (IBinder) -> Boolean = { binder ->
        ServerDiagnostics.readInfo(binder)?.version?.matches(PorterServiceVersion.installed) == true
    }

    /** Explicit and once-only, like [eu.darken.porter.manager.LaunchModeRecorder.attachToPorter]. */
    fun attachToPorter(context: Context) {
        if (!attached.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        scope.launch {
            ServerBinder.binder.collect { binder -> if (binder != null) offer(appContext, binder) }
        }
    }

    @VisibleForTesting
    internal fun offer(context: Context, binder: IBinder) {
        if (!PorterSettings.autoUpdateService || UserHandleCompat.myUserId() != 0) return
        val current = try {
            runsInstalledBuild(binder)
        } catch (e: Exception) {
            // A server that stopped answering is not one to replace; its successor connects anew.
            LOGGER.w(e, "Read the build of the connected service")
            return
        }
        if (!current) schedule(context)
    }
}
