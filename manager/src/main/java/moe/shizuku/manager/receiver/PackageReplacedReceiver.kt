package moe.shizuku.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.utils.UserHandleCompat
import moe.shizuku.manager.worker.ServiceUpdateWorker

class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED || UserHandleCompat.myUserId() != 0
            || !ShizukuSettings.getAutoUpdateService()) return
        ServiceUpdateWorker.schedule(context)
    }
}
