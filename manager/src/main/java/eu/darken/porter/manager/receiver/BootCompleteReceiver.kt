package eu.darken.porter.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import eu.darken.porter.manager.PorterSettings
import eu.darken.porter.manager.service.WatchdogService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // A root start blocks on the su shell; that must not happen on the receiver's main thread.
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                PorterReceiverStarter.start(context)
                if (PorterSettings.watchdog) WatchdogService.start(context)
            } finally {
                result.finish()
            }
        }
    }
}
