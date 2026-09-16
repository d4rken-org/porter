package eu.darken.porter.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import eu.darken.porter.manager.BuildConfig

class ManualStartReceiver : AuthenticatedReceiver() {
    override fun onAuthenticated(context: Context, intent: Intent) {
        val applicationId = BuildConfig.APPLICATION_ID
        if (intent.action != "${applicationId}.START") return

        PorterReceiverStarter.start(context)
    }
}