package eu.darken.porter.probe;

import android.app.Activity;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.widget.TextView;
import eu.darken.porter.sdk.Porter;
import eu.darken.porter.sdk.PorterServerInfo;

public class ProbeActivity extends Activity {
    private TextView status;
    private Porter.UserServiceArgs args;
    private boolean bound;
    /** Asks for an existing service without starting one, which is the noCreate hand-over path. */
    private boolean peek;
    private final Porter.OnBinderReceivedListener received = () -> runOnUiThread(this::connect);
    private final Porter.OnBinderDeadListener died = () -> report("BINDER_DEAD");
    private final Porter.OnRequestPermissionResultListener permission = (code, result) -> {
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
        boolean daemon = getIntent().getBooleanExtra("daemon", false);
        peek = getIntent().getBooleanExtra("peek", false);
        args = new Porter.UserServiceArgs(new ComponentName(this, ProbeService.class))
                .daemon(daemon).processNameSuffix("porter-probe").version(1);
        report("MODE daemon=" + daemon + " peek=" + peek);
        // Before any listener, so this reports the state selection resolved rather than one a
        // delivery has already changed.
        report("AVAILABILITY " + Porter.getAvailability(this));
        Porter.addBinderReceivedListenerSticky(received);
        Porter.addBinderDeadListener(died);
        Porter.addRequestPermissionResultListener(permission);
        if (!Porter.pingBinder()) report("WAITING_FOR_BINDER");
    }

    private void connect() {
        try {
            report("BINDER uid=" + Porter.getUid() + " version=" + Porter.getServerProtocolVersion());
            PorterServerInfo info = Porter.getServerInfo();
            report("BACKEND " + (info == null ? "none" : info.backend));
            if (Porter.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Porter.requestPermission(1);
                return;
            }
            boolean managerDenied = false;
            try { Porter.updateFlagsForUid(android.os.Process.myUid(), 6, 2); }
            catch (SecurityException expected) { managerDenied = true; }
            report("AUTHORIZED managerOperationDenied=" + managerDenied);
            if (peek) {
                int version = Porter.peekUserService(args, connection);
                report("PEEK version=" + version);
                bound = version >= 0;
                return;
            }
            Porter.bindUserService(args, connection);
            bound = true;
        } catch (Exception e) { report("FAILED " + e); }
    }

    private void report(String message) {
        Log.i("PorterProbe", getPackageName() + " " + message);
        runOnUiThread(() -> status.append(message + "\n"));
    }

    @Override public void onDestroy() {
        Porter.removeBinderReceivedListener(received);
        Porter.removeBinderDeadListener(died);
        Porter.removeRequestPermissionResultListener(permission);
        if (bound && Porter.pingBinder()) {
            try { Porter.unbindUserService(args, connection, true); }
            catch (RuntimeException e) { Log.w("PorterProbe", "Service disconnected during teardown", e); }
        }
        super.onDestroy();
    }
}
