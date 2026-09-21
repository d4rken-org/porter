package eu.darken.porter.manager.shell

import android.os.Handler
import android.os.IBinder
import android.os.Looper
import eu.darken.porter.manager.BuildConfig
import eu.darken.porter.porsh.Porsh
import eu.darken.porter.porsh.PorshConfig
import eu.darken.porter.protocol.PorterProtocol
import eu.darken.porter.sdk.PermissionState
import eu.darken.porter.sdk.Porter
import eu.darken.porter.sdk.PorterConnectionLostException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

class Shell : Porsh() {

    override fun requestPermission(onGrantedRunnable: Runnable) {
        val connection = Porter.connection.value ?: deny()
        when (val state = connection.checkPermission()) {
            PermissionState.Granted -> {
                cancelStartupTimeout()
                onGrantedRunnable.run()
            }
            is PermissionState.Denied -> if (state.permanentlyDenied) {
                deny()
            } else {
                scope.launch {
                    val answer = try {
                        connection.requestPermission()
                    } catch (e: PorterConnectionLostException) {
                        PermissionState.Denied(permanentlyDenied = false)
                    }
                    // The answer arrives on a binder thread; the shell starts on the main one.
                    mainHandler.post { if (answer == PermissionState.Granted) onGrantedRunnable.run() else deny() }
                }
                cancelStartupTimeout()
            }
        }
    }

    companion object {
        private const val STARTUP_TIMEOUT_MILLIS = 10_000L

        @Volatile
        private var startupWatchdog: Thread? = null

        /** Unconfined: a connection that is already published starts the shell in the same call. */
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

        /** Entered by porsh copies exported before the handshake carried a loader version. */
        @JvmStatic
        fun main(args: Array<String>, packageName: String, binder: IBinder, handler: Handler) =
            main(args, packageName, binder, handler, 0)

        @JvmStatic
        fun main(args: Array<String>, packageName: String, binder: IBinder, handler: Handler, loaderVersion: Int) {
            if (loaderVersion < BuildConfig.PORSH_LOADER_VERSION) {
                System.err.println(
                    "The porsh files in this terminal app are from an older Porter (loader $loaderVersion, current ${BuildConfig.PORSH_LOADER_VERSION}). " +
                        "Export them again from Porter to keep them working.",
                )
                System.err.flush()
            }
            // The loader timeout only covers binder delivery, not the user's permission decision.
            // It is replaced by a startup deadline, which is itself dropped once the wait becomes
            // a human decision.
            handler.removeCallbacksAndMessages(null)
            armStartupTimeout()
            PorshConfig.init(binder, PorterProtocol.DESCRIPTOR, PorterProtocol.TRANSACTION_PORSH_BASE)
            Porter.onBinderReceived(binder, packageName)
            scope.launch {
                Porter.connection.filterNotNull().first()
                // The shell starts on the main looper, whichever thread published the connection.
                if (Looper.myLooper() == Looper.getMainLooper()) Shell().start(args) else mainHandler.post { Shell().start(args) }
            }
        }

        private fun deny(): Nothing {
            System.err.println("Permission denied")
            System.err.flush()
            exitProcess(1)
        }

        // A thread, not handler.postDelayed: main runs on the main looper, so a blocked binder call
        // blocks the looper and a posted callback would never fire.
        private fun armStartupTimeout() {
            val watchdog = Thread {
                try {
                    Thread.sleep(STARTUP_TIMEOUT_MILLIS)
                } catch (e: InterruptedException) {
                    return@Thread
                }
                System.err.println("porsh timed out waiting for the Porter service. It may be busy or stopped; check that Porter is running and try again.")
                System.err.flush()
                exitProcess(1)
            }
            watchdog.isDaemon = true
            startupWatchdog = watchdog
            watchdog.start()
        }

        private fun cancelStartupTimeout() {
            val watchdog = startupWatchdog
            startupWatchdog = null
            watchdog?.interrupt()
        }
    }
}
