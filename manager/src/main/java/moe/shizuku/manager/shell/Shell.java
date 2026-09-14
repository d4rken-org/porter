package moe.shizuku.manager.shell;

import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;

import eu.darken.porter.porsh.Porsh;
import eu.darken.porter.porsh.PorshConfig;
import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuApiConstants;

public class Shell extends Porsh {

    private static final long STARTUP_TIMEOUT_MILLIS = 10_000;
    private static volatile Thread startupWatchdog;

    @Override
    public void requestPermission(Runnable onGrantedRunnable) {
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            cancelStartupTimeout();
            onGrantedRunnable.run();
        } else if (Shizuku.shouldShowRequestPermissionRationale()) {
            System.err.println("Permission denied");
            System.err.flush();
            System.exit(1);
        } else {
            Shizuku.addRequestPermissionResultListener(new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    Shizuku.removeRequestPermissionResultListener(this);

                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        onGrantedRunnable.run();
                    } else {
                        System.err.println("Permission denied");
                        System.err.flush();
                        System.exit(1);
                    }
                }
            });
            Shizuku.requestPermission(0);
            cancelStartupTimeout();
        }
    }

    public static void main(String[] args, String packageName, IBinder binder, Handler handler) {
        // The loader timeout only covers binder delivery, not the user's permission decision.
        // It is replaced by a startup deadline, which is itself dropped once the wait becomes
        // a human decision.
        handler.removeCallbacksAndMessages(null);
        armStartupTimeout();
        PorshConfig.init(binder, ShizukuApiConstants.BINDER_DESCRIPTOR, 30000);
        Shizuku.onBinderReceived(binder, packageName);
        Shizuku.addBinderReceivedListenerSticky(() -> {
            int version = Shizuku.getVersion();
            if (version < 12) {
                System.err.println("porsh requires server 12 (running " + version + ")");
                System.err.flush();
                System.exit(1);
            }
            new Shell().start(args);
        });
    }

    // A thread, not handler.postDelayed: main runs on the main looper, so a blocked binder call
    // blocks the looper and a posted callback would never fire.
    private static void armStartupTimeout() {
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(STARTUP_TIMEOUT_MILLIS);
            } catch (InterruptedException e) {
                return;
            }
            System.err.println("porsh timed out waiting for the Porter service. It may be busy or stopped; check that Porter is running and try again.");
            System.err.flush();
            System.exit(1);
        });
        watchdog.setDaemon(true);
        startupWatchdog = watchdog;
        watchdog.start();
    }

    private static void cancelStartupTimeout() {
        Thread watchdog = startupWatchdog;
        startupWatchdog = null;
        if (watchdog != null) watchdog.interrupt();
    }
}
