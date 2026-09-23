package eu.darken.porter.manager.receiver

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager
import eu.darken.porter.manager.PorterSettings
import eu.darken.porter.manager.receiver.PorterReceiverStarter
import eu.darken.porter.manager.utils.EnvironmentUtils
import eu.darken.porter.manager.worker.AdbStartWorker

class NotifCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WorkManager.getInstance(context).cancelUniqueWork("adb_start_worker")
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(PorterReceiverStarter.NOTIFICATION_ID)
        nm.cancel(AdbStartWorker.NOTIFICATION_ID)
    }
}
