package eu.darken.porter.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import eu.darken.porter.manager.PorterSettings
import eu.darken.porter.manager.utils.UserHandleCompat
import eu.darken.porter.manager.worker.ServiceUpdateWorker

class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED || UserHandleCompat.myUserId() != 0
            || !PorterSettings.autoUpdateService) return
        ServiceUpdateWorker.schedule(context)
    }
}
