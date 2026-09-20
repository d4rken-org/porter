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
import eu.darken.porter.sdk.PorterBinderWrapper;
import eu.darken.porter.sdk.PorterServerInfo;
import rikka.hidden.compat.PackageManagerApis;
import rikka.hidden.compat.util.SystemServiceBinder;

public class ProbeActivity extends Activity {
    private TextView status;
    private Porter.UserServiceArgs args;
    private boolean bound;
    /** Asks for an existing service without starting one, which is the noCreate hand-over path. */
    private boolean peek;
    /** Announces the binder this process already holds once more, on the next connect() only. */
    private boolean redeliver;
    /** Runs one system service call twice, through the wire and as this process. */
    private boolean forward;
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
        redeliver = getIntent().getBooleanExtra("redeliver", false);
        forward = getIntent().getBooleanExtra("forward", false);
        args = new Porter.UserServiceArgs(new ComponentName(this, ProbeService.class))
                .daemon(daemon).processNameSuffix("porter-probe").version(1);
        report("MODE daemon=" + daemon + " peek=" + peek + " forward=" + forward);
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
            if (forward) forwardOne();
            if (redeliver) {
                // Cleared first: a delivery that is not refused publishes a session and schedules
                // connect() again, and this would then redeliver without bound.
                redeliver = false;
                Porter.onBinderReceived(Porter.getBinder(), getPackageName());
                report("REDELIVERED");
            }
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

    /**
     * The same query twice: once through a wrapped system service binder, which the wire forwards
     * and the server answers, and once against this process's own PackageManager. This app declares
     * no queries, so the counts differ by package visibility, and equal counts mean the wrapped
     * call was answered as this app rather than forwarded.
     */
    private void forwardOne() {
        // Installed before any other call through this library, which keeps the first binder it
        // resolved for a service: a call made ahead of this would cache the unwrapped one.
        SystemServiceBinder.setOnGetBinderListener(PorterBinderWrapper::new);
        int forwarded = PackageManagerApis.getInstalledPackagesNoThrow(0L, 0).size();
        int direct = getPackageManager().getInstalledPackages(0).size();
        report("FORWARDED forwarded=" + forwarded + " direct=" + direct);
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
