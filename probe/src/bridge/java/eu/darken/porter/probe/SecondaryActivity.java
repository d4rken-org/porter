package eu.darken.porter.probe;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import eu.darken.porter.sdk.Porter;
import eu.darken.porter.sdk.PorterApiProvider;
import eu.darken.porter.sdk.PorterServerInfo;

/** A second app process that uses the SDK and never receives a delivery from the server itself. */
public class SecondaryActivity extends Activity {
    private TextView status;
    private final Porter.OnBinderReceivedListener received = () -> runOnUiThread(this::describe);
    private final Porter.OnBinderDeadListener died = () -> report("SECONDARY BINDER_DEAD");

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        status = new TextView(this);
        status.setTextSize(20);
        setContentView(status);
        report("SECONDARY STARTED");
        report("SECONDARY AVAILABILITY " + Porter.getAvailability(this));
        // Registers the broadcast receiver, installs the post-death refetch hook, and fetches once.
        PorterApiProvider.requestBinderForNonProviderProcess(this);
        Porter.addBinderReceivedListenerSticky(received);
        Porter.addBinderDeadListener(died);
    }

    private void describe() {
        try {
            PorterServerInfo info = Porter.getServerInfo();
            report("SECONDARY BINDER uid=" + Porter.getUid()
                    + " backend=" + (info == null ? "none" : info.backend));
        } catch (RuntimeException e) {
            report("SECONDARY FAILED " + e);
        }
    }

    private void report(String message) {
        Log.i("PorterProbe", getPackageName() + " " + message);
        runOnUiThread(() -> status.append(message + "\n"));
    }

    @Override public void onDestroy() {
        Porter.removeBinderReceivedListener(received);
        Porter.removeBinderDeadListener(died);
        super.onDestroy();
    }
}
