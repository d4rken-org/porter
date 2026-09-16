package eu.darken.porter.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import eu.darken.porter.manager.BuildConfig
import eu.darken.porter.manager.R
import eu.darken.porter.manager.service.ServiceStatusRepository
import eu.darken.porter.manager.utils.PorterStateMachine

class ManualStopReceiver : AuthenticatedReceiver() {
    override fun onAuthenticated(context: Context, intent: Intent) {
        val applicationId = BuildConfig.APPLICATION_ID
        if (intent.action != "${applicationId}.STOP") return
        if (!PorterStateMachine.instance.isRunning()) return

        ServiceStatusRepository.stop()
    }
}