package eu.darken.porter.compat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BinderRequestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if ("rikka.shizuku.intent.action.REQUEST_BINDER" != intent.action || !PorterIdentity.isInstalled(context)) return
        val forward = Intent("eu.darken.porter.intent.action.REQUEST_BINDER")
            .setPackage(PorterIdentity.PACKAGE)
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        if (intent.hasExtra("data")) forward.putExtra("data", intent.getBundleExtra("data"))
        context.sendBroadcast(forward)
    }
}
