package eu.darken.porter.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class NotifRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PorterReceiverStarter.updateNotification(
            context,
            PorterReceiverStarter.WorkerState.RUNNING
        )
    }
}