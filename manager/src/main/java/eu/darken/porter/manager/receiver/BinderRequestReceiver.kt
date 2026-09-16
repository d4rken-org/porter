package eu.darken.porter.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import eu.darken.porter.manager.shell.ShellBinderRequestHandler

class BinderRequestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ShellBinderRequestHandler.handleRequest(context, intent)
    }
}
