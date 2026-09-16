package eu.darken.porter.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import eu.darken.porter.manager.worker.AdbStartWorker

class NotifAttemptReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AdbStartWorker.enqueue(context)
    }
}
