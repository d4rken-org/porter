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
    private boolean bound;
    /** The binder a connect was already made for; UI thread only. */
    private IBinder connectedTo;
    /** Asks for an existing service without starting one, which is the noCreate hand-over path. */
    private boolean peek;
    private final Shizuku.OnBinderReceivedListener received = () -> runOnUiThread(this::onBinderAvailable);
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
        boolean daemon = getIntent().getBooleanExtra("daemon", false);
        peek = getIntent().getBooleanExtra("peek", false);
        args = new Shizuku.UserServiceArgs(new ComponentName(this, ProbeService.class))
                .daemon(daemon).processNameSuffix("porter-probe").version(1);
        report("MODE daemon=" + daemon + " peek=" + peek);
        Shizuku.addBinderReceivedListenerSticky(received);
        Shizuku.addBinderDeadListener(died);
        Shizuku.addRequestPermissionResultListener(permission);
        if (!Shizuku.pingBinder()) {
            report("WAITING_FOR_BINDER");
            return;
        }
        // The provider delivers the binder on its own thread and can do so while this one is still
        // inside addBinderReceivedListenerSticky, past both the readiness check and the dispatch,
        // so the sticky listener never fires for a binder that is already here. Catching up at the
        // back of the queue picks that up without acting on a delivery the listener also saw.
        status.post(this::onBinderAvailable);
    }

    /**
     * Both paths a delivery can arrive by, collapsed to one connect per binder. Keyed on the
     * binder itself rather than a flag: a replacement arrives as a different one, and its death
     * notification is posted, so it can land after the replacement has already been handed over.
     * The permission result does not come through here; its connect has to run after this one
     * returned early having asked for the grant.
     */
    private void onBinderAvailable() {
        IBinder current = Shizuku.getBinder();
        if (current == null || current == connectedTo) return;
        connectedTo = current;
        // onBinderReceived publishes the binder before it attaches this client, so a catch-up can
        // land on a server that does not know us yet. That attempt must not count as the one.
        if (!connect()) connectedTo = null;
    }

    private boolean connect() {
        try {
            report("BINDER uid=" + Shizuku.getUid() + " version=" + Shizuku.getVersion());
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(1);
                return true;
            }
            boolean managerDenied = false;
            try { Shizuku.updateFlagsForUid(android.os.Process.myUid(), 6, 2); }
            catch (SecurityException expected) { managerDenied = true; }
            report("AUTHORIZED managerOperationDenied=" + managerDenied);
            if (peek) {
                int version = Shizuku.peekUserService(args, connection);
                report("PEEK version=" + version);
                bound = version >= 0;
                return true;
            }
            Shizuku.bindUserService(args, connection);
            bound = true;
            return true;
        } catch (Exception e) { report("FAILED " + e); return false; }
    }

    private void report(String message) {
        Log.i("PorterProbe", getPackageName() + " " + message);
        runOnUiThread(() -> status.append(message + "\n"));
    }

    @Override public void onDestroy() {
        Shizuku.removeBinderReceivedListener(received);
        Shizuku.removeBinderDeadListener(died);
        Shizuku.removeRequestPermissionResultListener(permission);
        if (bound && Shizuku.pingBinder()) {
            try { Shizuku.unbindUserService(args, connection, true); }
            catch (RuntimeException e) { Log.w("PorterProbe", "Service disconnected during teardown", e); }
        }
        super.onDestroy();
    }
}
