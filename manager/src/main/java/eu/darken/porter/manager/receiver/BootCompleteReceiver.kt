package eu.darken.porter.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import eu.darken.porter.manager.PorterSettings
import eu.darken.porter.manager.service.WatchdogService

class BootCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        PorterReceiverStarter.start(context)
        if(PorterSettings.getWatchdog()) WatchdogService.start(context)
    }
}