package eu.darken.porter.compat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BinderRequestReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!"rikka.shizuku.intent.action.REQUEST_BINDER".equals(intent.getAction())
                || !PorterIdentity.isInstalled(context)) return;
        Intent forward = new Intent("eu.darken.porter.intent.action.REQUEST_BINDER")
                .setClassName(PorterIdentity.PACKAGE, "moe.shizuku.manager.receiver.BinderRequestReceiver");
        if (intent.hasExtra("data")) forward.putExtra("data", intent.getBundleExtra("data"));
        context.sendBroadcast(forward);
    }

}
