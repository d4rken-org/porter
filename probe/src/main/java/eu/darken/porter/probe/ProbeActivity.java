package eu.darken.porter.probe;

import android.app.Activity;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.widget.TextView;
import rikka.shizuku.Shizuku;

public class ProbeActivity extends Activity {
    private TextView status;
    private Shizuku.UserServiceArgs args;
    private final Shizuku.OnBinderReceivedListener received = () -> runOnUiThread(this::connect);
    private final Shizuku.OnBinderDeadListener died = () -> report("BINDER_DEAD");
    private final Shizuku.OnRequestPermissionResultListener permission = (code, result) -> {
        if (result == PackageManager.PERMISSION_GRANTED) connect(); else report("DENIED");
    };
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            try {
                IProbe probe = IProbe.Stub.asInterface(binder);
                report("USER_SERVICE uid=" + probe.uid() + " file=" + probe.readFile("/data/local/tmp/porter-probe.txt"));
            } catch (Exception e) { report("FAILED " + e); }
        }
        @Override public void onServiceDisconnected(ComponentName name) { report("USER_SERVICE_DISCONNECTED"); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        status = new TextView(this);
        status.setTextSize(20);
        setContentView(status);
        args = new Shizuku.UserServiceArgs(new ComponentName(this, ProbeService.class))
                .daemon(false).processNameSuffix("porter-probe").version(1);
        Shizuku.addBinderReceivedListenerSticky(received);
        Shizuku.addBinderDeadListener(died);
        Shizuku.addRequestPermissionResultListener(permission);
        if (!Shizuku.pingBinder()) report("WAITING_FOR_BINDER");
    }

    private void connect() {
        try {
            report("BINDER uid=" + Shizuku.getUid() + " version=" + Shizuku.getVersion());
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(1);
                return;
            }
            boolean managerDenied = false;
            try { Shizuku.updateFlagsForUid(android.os.Process.myUid(), 6, 2); }
            catch (SecurityException expected) { managerDenied = true; }
            report("AUTHORIZED managerOperationDenied=" + managerDenied);
            Shizuku.bindUserService(args, connection);
        } catch (Exception e) { report("FAILED " + e); }
    }

    private void report(String message) {
        Log.i("PorterProbe", getPackageName() + " " + message);
        runOnUiThread(() -> status.append(message + "\n"));
    }

    @Override public void onDestroy() {
        Shizuku.removeBinderReceivedListener(received);
        Shizuku.removeBinderDeadListener(died);
        Shizuku.removeRequestPermissionResultListener(permission);
        if (Shizuku.pingBinder()) Shizuku.unbindUserService(args, connection, true);
        super.onDestroy();
    }
}
