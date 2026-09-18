package eu.darken.porter.probe;

import android.app.Application;
import android.os.Build;
import android.util.Log;
import eu.darken.porter.sdk.PorterApiProvider;
import java.io.FileInputStream;
import java.io.IOException;

public class ProbeApplication extends Application {

    @Override public void onCreate() {
        super.onCreate();
        String name = processName();
        boolean provider = !name.endsWith(":secondary");
        // Both flags are process-static, so this has to run before anything touches the SDK: the
        // provider process enables the broadcast, the secondary marks itself as non-provider.
        PorterApiProvider.enableMultiProcessSupport(provider);
        Log.i("PorterProbe", getPackageName() + " APPLICATION process=" + name + " provider=" + provider);
    }

    private String processName() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return Application.getProcessName();
        try (FileInputStream cmdline = new FileInputStream("/proc/self/cmdline")) {
            StringBuilder name = new StringBuilder();
            for (int read = cmdline.read(); read > 0; read = cmdline.read()) name.append((char) read);
            return name.toString();
        } catch (IOException e) {
            // Guessing here would let the secondary process claim the provider role.
            throw new IllegalStateException("cannot read this process's name", e);
        }
    }
}
