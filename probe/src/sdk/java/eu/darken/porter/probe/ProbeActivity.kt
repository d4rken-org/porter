package eu.darken.porter.probe

import android.app.Activity
import android.content.ComponentName
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import eu.darken.porter.common.AppTransactions
import eu.darken.porter.protocol.PorterProtocol
import android.widget.TextView
import eu.darken.porter.sdk.PermissionState
import eu.darken.porter.sdk.Porter
import eu.darken.porter.sdk.PorterConnection
import eu.darken.porter.sdk.PorterConnectionLostException
import eu.darken.porter.sdk.UserServiceArgs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.hidden.compat.PackageManagerApis
import rikka.hidden.compat.util.SystemServiceBinder

class ProbeActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var args: UserServiceArgs
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var bound = false
    private var binding: Job? = null
    private var permissionWatch: Job? = null

    /** Asks for an existing service without starting one, which is the noCreate hand-over path. */
    private var peek = false

    /** Announces the binder this process already holds once more, on the next connect() only. */
    private var redeliver = false

    /** Runs one system service call twice, through the wire and as this process. */
    private var forward = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        status = TextView(this)
        status.textSize = 20f
        setContentView(status)
        val daemon = intent.getBooleanExtra("daemon", false)
        peek = intent.getBooleanExtra("peek", false)
        redeliver = intent.getBooleanExtra("redeliver", false)
        forward = intent.getBooleanExtra("forward", false)
        args = UserServiceArgs(ComponentName(this, ProbeService::class.java), processNameSuffix = "porter-probe", version = 1, daemon = daemon)
        report("MODE daemon=$daemon peek=$peek forward=$forward")
        scope.launch {
            // Before collecting, so this reports the state selection resolved rather than one a
            // delivery has already changed.
            report("AVAILABILITY " + availabilityToken(Porter.availability(this@ProbeActivity)))
            if (Porter.connection.value?.isAlive() != true) report("WAITING_FOR_BINDER")
            var previous: PorterConnection? = null
            Porter.connection.collect { connection ->
                if (connection == null) {
                    if (previous != null) report("BINDER_DEAD")
                } else {
                    watchPermission(connection)
                    connect(connection)
                }
                previous = connection
            }
        }
    }

    /** Logs every state [PorterConnection.permission] emits. */
    private fun watchPermission(connection: PorterConnection) {
        permissionWatch?.cancel()
        permissionWatch = scope.launch {
            connection.permission.collect { state ->
                report(if (state is PermissionState.Denied) "PERMISSION denied permanently=${state.permanentlyDenied}" else "PERMISSION granted")
            }
        }
    }

    private suspend fun connect(connection: PorterConnection) {
        try {
            report("BINDER uid=" + connection.uid + " version=" + connection.serverInfo.version)
            report("BACKEND " + connection.serverInfo.backend)
            if (connection.checkPermission() != PermissionState.Granted) {
                scope.launch {
                    val answer = try {
                        connection.requestPermission()
                    } catch (e: PorterConnectionLostException) {
                        return@launch
                    }
                    if (answer == PermissionState.Granted) connect(connection) else report("DENIED")
                }
                return
            }
            // The manager's own binder is handed out over an app-owned code, to the manager alone.
            var managerDenied = false
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(PorterProtocol.DESCRIPTOR)
                connection.binder.transact(AppTransactions.GET_MANAGER, data, reply, 0)
                reply.readException()
            } catch (expected: SecurityException) {
                managerDenied = true
            } finally {
                data.recycle()
                reply.recycle()
            }
            report("AUTHORIZED managerOperationDenied=$managerDenied")
            if (forward) forwardOne(connection)
            if (redeliver) {
                // Cleared first: a delivery that is not refused publishes a connection and runs
                // connect() again, and this would then redeliver without bound.
                redeliver = false
                Porter.onBinderReceived(connection.binder, packageName)
                report("REDELIVERED")
            }
            if (peek) {
                val version = connection.peekUserService(args) ?: -1
                report("PEEK version=$version")
                bound = version >= 0
                return
            }
            binding = scope.launch {
                try {
                    connection.userService(args).collect { binder -> onServiceConnected(binder) }
                    report("USER_SERVICE_DISCONNECTED")
                } catch (e: Exception) {
                    report("FAILED $e")
                }
            }
            bound = true
        } catch (e: Exception) {
            report("FAILED $e")
        }
    }

    /**
     * The same query twice: once through a wrapped system service binder, which the wire forwards
     * and the server answers, and once against this process's own PackageManager. This app declares
     * no queries, so the counts differ by package visibility, and equal counts mean the wrapped
     * call was answered as this app rather than forwarded.
     */
    private fun forwardOne(connection: PorterConnection) {
        // The wrapper forwards the transaction, but the app still links the hidden method that
        // writes it, and the platform blocks that from an app: on API 36 the call below is denied
        // before any binder is reached. Every app integrating this way needs the exemption.
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            HiddenApiBypass.setHiddenApiExemptions("")
        }
        // Installed before any other call through this library, which keeps the first binder it
        // resolved for a service: a call made ahead of this would cache the unwrapped one.
        SystemServiceBinder.setOnGetBinderListener { binder -> connection.wrap(binder) }
        val forwarded = PackageManagerApis.getInstalledPackagesNoThrow(0L, 0).size
        val direct = packageManager.getInstalledPackages(0).size
        report("FORWARDED forwarded=$forwarded direct=$direct")
    }

    private fun onServiceConnected(binder: IBinder) {
        try {
            val probe = IProbe.Stub.asInterface(binder)
            report("USER_SERVICE uid=" + probe.uid() + " file=" + probe.readFile("/data/local/tmp/porter-probe.txt"))
        } catch (e: Exception) {
            report("FAILED $e")
        }
    }

    private fun report(message: String) {
        Log.i("PorterProbe", "$packageName $message")
        runOnUiThread { status.append(message + "\n") }
    }

    override fun onDestroy() {
        val connection = Porter.connection.value
        // Sent before the scope is cancelled below, which would end a stop launched into it.
        if (bound && connection != null) runBlocking {
            if (connection.isAlive()) {
                try {
                    connection.stopUserService(args)
                } catch (e: RuntimeException) {
                    Log.w("PorterProbe", "Service disconnected during teardown", e)
                }
            }
        }
        scope.cancel()
        super.onDestroy()
    }
}
