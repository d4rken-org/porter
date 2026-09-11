package moe.shizuku.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.R
import moe.shizuku.manager.service.ServiceStatusRepository
import moe.shizuku.manager.utils.ShizukuStateMachine

class ManualStopReceiver : AuthenticatedReceiver() {
    override fun onAuthenticated(context: Context, intent: Intent) {
        val applicationId = BuildConfig.APPLICATION_ID
        if (intent.action != "${applicationId}.STOP") return
        if (!ShizukuStateMachine.isRunning()) return

        ServiceStatusRepository.stop()
    }
}