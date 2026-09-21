package eu.darken.porter.probe

import android.app.Activity
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.TextView
import rikka.shizuku.Shizuku

class ProbeActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var args: Shizuku.UserServiceArgs
    private var bound = false

    /** The binder a connect was already made for; UI thread only. */
    private var connectedTo: IBinder? = null

    /** Asks for an existing service without starting one, which is the noCreate hand-over path. */
    private var peek = false

    private val received = Shizuku.OnBinderReceivedListener { runOnUiThread { onBinderAvailable() } }
    private val died = Shizuku.OnBinderDeadListener { report("BINDER_DEAD") }
    private val permission = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result == PackageManager.PERMISSION_GRANTED) connect() else report("DENIED")
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            try {
                val probe = IProbe.Stub.asInterface(binder)
                report("USER_SERVICE uid=" + probe.uid() + " file=" + probe.readFile("/data/local/tmp/porter-probe.txt"))
            } catch (e: Exception) {
                report("FAILED $e")
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            report("USER_SERVICE_DISCONNECTED")
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        status = TextView(this)
        status.textSize = 20f
        setContentView(status)
        val daemon = intent.getBooleanExtra("daemon", false)
        peek = intent.getBooleanExtra("peek", false)
        args = Shizuku.UserServiceArgs(ComponentName(this, ProbeService::class.java))
            .daemon(daemon).processNameSuffix("porter-probe").version(1)
        report("MODE daemon=$daemon peek=$peek")
        Shizuku.addBinderReceivedListenerSticky(received)
        Shizuku.addBinderDeadListener(died)
        Shizuku.addRequestPermissionResultListener(permission)
        if (!Shizuku.pingBinder()) {
            report("WAITING_FOR_BINDER")
            return
        }
        // The provider delivers the binder on its own thread and can do so while this one is still
        // inside addBinderReceivedListenerSticky, past both the readiness check and the dispatch,
        // so the sticky listener never fires for a binder that is already here. Catching up at the
        // back of the queue picks that up without acting on a delivery the listener also saw.
        status.post { onBinderAvailable() }
    }

    /**
     * Both paths a delivery can arrive by, collapsed to one connect per binder. Keyed on the
     * binder itself rather than a flag: a replacement arrives as a different one, and its death
     * notification is posted, so it can land after the replacement has already been handed over.
     * The permission result does not come through here; its connect has to run after this one
     * returned early having asked for the grant.
     */
    private fun onBinderAvailable() {
        val current = Shizuku.getBinder()
        if (current == null || current === connectedTo) return
        connectedTo = current
        // onBinderReceived publishes the binder before it attaches this client, so a catch-up can
        // land on a server that does not know us yet. That attempt must not count as the one.
        if (!connect()) connectedTo = null
    }

    private fun connect(): Boolean {
        try {
            report("BINDER uid=" + Shizuku.getUid() + " version=" + Shizuku.getVersion())
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(1)
                return true
            }
            var managerDenied = false
            try {
                Shizuku.updateFlagsForUid(android.os.Process.myUid(), 6, 2)
            } catch (expected: SecurityException) {
                managerDenied = true
            }
            report("AUTHORIZED managerOperationDenied=$managerDenied")
            if (peek) {
                val version = Shizuku.peekUserService(args, connection)
                report("PEEK version=$version")
                bound = version >= 0
                return true
            }
            Shizuku.bindUserService(args, connection)
            bound = true
            return true
        } catch (e: Exception) {
            report("FAILED $e")
            return false
        }
    }

    private fun report(message: String) {
        Log.i("PorterProbe", "$packageName $message")
        runOnUiThread { status.append(message + "\n") }
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(received)
        Shizuku.removeBinderDeadListener(died)
        Shizuku.removeRequestPermissionResultListener(permission)
        if (bound && Shizuku.pingBinder()) {
            try {
                Shizuku.unbindUserService(args, connection, true)
            } catch (e: RuntimeException) {
                Log.w("PorterProbe", "Service disconnected during teardown", e)
            }
        }
        super.onDestroy()
    }
}
