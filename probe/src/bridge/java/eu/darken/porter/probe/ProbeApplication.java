package eu.darken.porter.probe;

import android.app.Application;
import android.os.Build;
import android.util.Log;
import eu.darken.porter.sdk.PorterApiProvider;
import java.io.FileInputStream;
import java.io.IOException;

public class ProbeApplication extends Application {

    // Content providers are installed before Application.onCreate, and the provider only announces
    // a delivery once this is set. A class initializer is the earliest point this process has.
    static {
        PorterApiProvider.enableMultiProcessSupport(!processName().endsWith(":secondary"));
    }

    @Override public void onCreate() {
        super.onCreate();
        String name = processName();
        boolean provider = !name.endsWith(":secondary");
        Log.i("PorterProbe", getPackageName() + " APPLICATION process=" + name + " provider=" + provider);
    }

    private static String processName() {
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
