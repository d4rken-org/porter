package eu.darken.porter.probe

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import eu.darken.porter.sdk.Porter
import eu.darken.porter.sdk.PorterApiProvider
import eu.darken.porter.sdk.PorterConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** A second app process that uses the SDK and never receives a delivery from the server itself. */
class SecondaryActivity : Activity() {

    private lateinit var status: TextView
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        status = TextView(this)
        status.textSize = 20f
        setContentView(status)
        report("SECONDARY STARTED")
        scope.launch {
            // Read before the fetch below is started, which could otherwise connect first.
            report("SECONDARY AVAILABILITY " + availabilityToken(Porter.availability(this@SecondaryActivity)))
            // Registers the broadcast receiver, installs the post-death refetch hook, and fetches once.
            PorterApiProvider.requestBinderForNonProviderProcess(this@SecondaryActivity)
            var previous: PorterConnection? = null
            Porter.connection.collect { connection ->
                if (connection == null) {
                    if (previous != null) report("SECONDARY BINDER_DEAD")
                } else {
                    describe(connection)
                }
                previous = connection
            }
        }
    }

    private fun describe(connection: PorterConnection) {
        try {
            report("SECONDARY BINDER uid=" + connection.uid + " backend=" + connection.serverInfo.backend)
        } catch (e: RuntimeException) {
            report("SECONDARY FAILED $e")
        }
    }

    private fun report(message: String) {
        Log.i("PorterProbe", "$packageName $message")
        runOnUiThread { status.append(message + "\n") }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
