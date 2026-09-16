package eu.darken.porter.starter;

import android.content.IContentProvider;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.util.Pair;

import java.util.Locale;

import eu.darken.porter.common.UserServiceLaunch;
import eu.darken.porter.protocol.PorterProtocol;
import eu.darken.porter.starter.BuildConfig;
import eu.darken.porter.starter.util.IContentProviderCompat;
import rikka.hidden.compat.ActivityManagerApis;
import rikka.shizuku.server.UserService;

public class ServiceStarter {

    private static final String TAG = "PorterServiceStarter";

    /** What the attach in {@link #sendBinder} waits for the manager's state machine, to the ms. */
    private static final long LAUNCH_TOKEN_BINDER_TIMEOUT = 5000;
    private static final long LAUNCH_TOKEN_BINDER_RETRY = 250;

    public static final String DEBUG_ARGS;

    static {
        int sdk = Build.VERSION.SDK_INT;
        if (sdk >= 30) {
            DEBUG_ARGS = "-Xcompiler-option" + " --debuggable" +
                    " -XjdwpProvider:adbconnection" +
                    " -XjdwpOptions:suspend=n,server=y";
        } else if (sdk >= 28) {
            DEBUG_ARGS = "-Xcompiler-option" + " --debuggable" +
                    " -XjdwpProvider:internal" +
                    " -XjdwpOptions:transport=dt_android_adb,suspend=n,server=y";
        } else {
            DEBUG_ARGS = "-Xcompiler-option" + " --debuggable" +
                    " -agentlib:jdwp=transport=dt_android_adb,suspend=n,server=y";
        }
    }

    private static final String USER_SERVICE_CMD_FORMAT = "(CLASSPATH='%s' %s%s /system/bin " +
            "--nice-name='%s' eu.darken.porter.starter.ServiceStarter " +
            "--manager='%s' --token='%s' --package='%s' --class='%s' --uid=%d%s)&";

    // DeathRecipient will automatically be unlinked when all references to the
    // binder is dropped, so we hold the reference here.
    @SuppressWarnings("FieldCanBeLocal")
    private static IBinder shizukuBinder;

    public static String commandForUserService(String appProcess, String managerApkPath, String managerPackageName, String token, String packageName, String classname, String processNameSuffix, int callingUid, boolean debug) {
        String processName = String.format("%s:%s", packageName, processNameSuffix);
        return String.format(Locale.ENGLISH, USER_SERVICE_CMD_FORMAT,
                managerApkPath, appProcess, debug ? (" " + DEBUG_ARGS) : "",
                processName,
                managerPackageName, token, packageName, classname, callingUid, debug ? (" " + "--debug-name=" + processName) : "");
    }

    private static String managerPackageName = BuildConfig.MANAGER_APPLICATION_ID;

    public static void main(String[] args) {
        if (Looper.getMainLooper() == null) {
            Looper.prepareMainLooper();
        }

        String launchToken = null;
        for (String arg : args) {
            if (arg.startsWith("--manager=")) {
                managerPackageName = arg.substring("--manager=".length());
            } else if (arg.startsWith("--token=")) {
                launchToken = arg.substring("--token=".length());
            }
        }

        // Before UserService.create, because that is where the client's code is loaded into this
        // privileged process. It is the revocation case this exists for: setAccessPaused and a
        // permission revocation both drop records, and without this a launch already in flight runs
        // the app's code once regardless. It is not a time bound - the framework bootstrap inside
        // create() can stretch - and it is not what catches a signer substitution.
        if (!isLaunchTokenLive(launchToken)) {
            Log.w(TAG, "user service token is not live, exiting");
            System.exit(1);
            return;
        }

        IBinder service;
        String token;

        UserService.setTag(TAG);
        Pair<IBinder, String> result = UserService.create(args);

        if (result == null) {
            System.exit(1);
            return;
        }

        service = result.first;
        token = result.second;

        if (!sendBinder(service, token)) {
            System.exit(1);
        }

        Looper.loop();
        System.exit(0);

        Log.i(TAG, "service exited");
    }

    /**
     * Where the launch-token check reads the manager's server binder from. A read yields the live
     * binder, or null while the manager has none to hand over.
     */
    interface BinderSource {
        IBinder read() throws Throwable;
    }

    /** The waiting, injectable: measured in real time a never-warming manager costs the full 5s. */
    interface Pacer {
        long now();

        void pause(long millis) throws InterruptedException;
    }

    private static final Pacer SYSTEM_PACER = new Pacer() {
        @Override
        public long now() {
            return SystemClock.uptimeMillis();
        }

        @Override
        public void pause(long millis) throws InterruptedException {
            Thread.sleep(millis);
        }
    };

    private static boolean isLaunchTokenLive(String token) {
        ManagerBinderSource source = new ManagerBinderSource(
                managerPackageName + PorterProtocol.PROVIDER_AUTHORITY_SUFFIX, 0);
        try {
            return isLaunchTokenLive(token, source, SYSTEM_PACER);
        } finally {
            source.close();
        }
    }

    /**
     * Asks the server whether {@code token} still belongs to a live record, through the manager's
     * provider. Fails closed, but not on the first null: the manager this launch runs against may be
     * one the platform just killed and this very call is restarting, and the attach that follows
     * waits the same 5s for its state machine. Only availability is retried - a binder that answers
     * "not live" has answered.
     */
    static boolean isLaunchTokenLive(String token, BinderSource source, Pacer pacer) {
        if (token == null) {
            Log.e(TAG, "no --token= to validate");
            return false;
        }
        try {
            long started = pacer.now();
            IBinder binder = source.read();
            while (binder == null && pacer.now() - started < LAUNCH_TOKEN_BINDER_TIMEOUT) {
                pacer.pause(LAUNCH_TOKEN_BINDER_RETRY);
                binder = source.read();
            }
            if (binder == null) {
                Log.e(TAG, String.format(Locale.ENGLISH,
                        "no server binder to validate against after %dms", LAUNCH_TOKEN_BINDER_TIMEOUT));
                return false;
            }
            return queryTokenLive(binder, token);
        } catch (Throwable tr) {
            Log.e(TAG, "failed to validate user service token", tr);
            return false;
        }
    }

    /** Holds one external provider reference per {@link #read()} until {@link #close()}. */
    private static final class ManagerBinderSource implements BinderSource {

        private final String name;
        private final int userId;
        private int references;

        ManagerBinderSource(String name, int userId) {
            this.name = name;
            this.userId = userId;
        }

        @Override
        public IBinder read() throws Throwable {
            // On every attempt, because this call is what starts the manager's process: retrying
            // against a reference taken while it was dead would wait for a process nobody asked to
            // come up. Each one taken is given back in close().
            IContentProvider provider = ActivityManagerApis.getContentProviderExternal(name, userId, null, name);
            if (provider == null) {
                Log.e(TAG, String.format("provider is null %s %d", name, userId));
                return null;
            }
            references++;
            // getBinder hands back the server binder without attaching. Both layers reject null extras.
            Bundle reply = IContentProviderCompat.call(provider, null, null, name,
                    PorterProtocol.DELIVERY_METHOD_GET_BINDER, null, new Bundle());
            if (reply == null) {
                return null;
            }
            IBinder serverBinder = reply.getBinder(PorterProtocol.DELIVERY_EXTRA_BINDER);
            if (serverBinder == null || !serverBinder.pingBinder()) {
                return null;
            }
            return serverBinder;
        }

        void close() {
            while (references > 0) {
                references--;
                try {
                    ActivityManagerApis.removeContentProviderExternal(name, null);
                } catch (Throwable tr) {
                    Log.w(TAG, "removeContentProviderExternal", tr);
                }
            }
        }
    }

    private static boolean queryTokenLive(IBinder binder, String token) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(PorterProtocol.DESCRIPTOR);
            data.writeString(token);
            if (!binder.transact(UserServiceLaunch.TRANSACTION, data, reply, 0)) return false;
            reply.readException();
            return reply.readInt() != 0;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static boolean sendBinder(IBinder binder, String token) {
        return sendBinder(binder, token, true);
    }

    private static boolean sendBinder(IBinder binder, String token, boolean retry) {
        String name = managerPackageName + PorterProtocol.PROVIDER_AUTHORITY_SUFFIX;
        int userId = 0;
        IContentProvider provider = null;

        try {
            provider = ActivityManagerApis.getContentProviderExternal(name, userId, null, name);
            if (provider == null) {
                Log.e(TAG, String.format("provider is null %s %d", name, userId));
                return false;
            }
            if (!provider.asBinder().pingBinder()) {
                Log.e(TAG, String.format("provider is dead %s %d", name, userId));

                if (retry) {
                    // For unknown reason, sometimes this could happens
                    // Kill Shizuku app and try again could work
                    ActivityManagerApis.forceStopPackageNoThrow(managerPackageName, userId);
                    Log.e(TAG, String.format("kill %s in user %d and try again", managerPackageName, userId));
                    Thread.sleep(1000);
                    return sendBinder(binder, token, false);
                }
                return false;
            }

            if (!retry) {
                Log.e(TAG, "retry works");
            }

            Bundle extra = new Bundle();
            extra.putBinder(PorterProtocol.DELIVERY_EXTRA_BINDER, binder);
            extra.putString(PorterProtocol.USER_SERVICE_TOKEN, token);

            Bundle reply = IContentProviderCompat.call(provider, null, null, name,
                    PorterProtocol.DELIVERY_METHOD_SEND_USER_SERVICE, null, extra);

            if (reply != null) {
                Log.i(TAG, String.format("send binder to %s in user %d", managerPackageName, userId));
                IBinder serverBinder = reply.getBinder(PorterProtocol.DELIVERY_EXTRA_BINDER);

                if (serverBinder != null && serverBinder.pingBinder()) {
                    shizukuBinder = serverBinder;
                    shizukuBinder.linkToDeath(() -> {
                        Log.i(TAG, "exiting...");
                        System.exit(0);
                    }, 0);
                    return true;
                } else {
                    Log.w(TAG, "server binder not received");
                }
            }

            return false;
        } catch (Throwable tr) {
            Log.e(TAG, String.format("failed send binder to %s in user %d", managerPackageName, userId), tr);
            return false;
        } finally {
            if (provider != null) {
                try {
                    ActivityManagerApis.removeContentProviderExternal(name, null);
                } catch (Throwable tr) {
                    Log.w(TAG, "removeContentProviderExternal", tr);
                }
            }
        }
    }
}
