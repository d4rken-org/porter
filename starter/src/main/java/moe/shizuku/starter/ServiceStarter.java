package moe.shizuku.starter;

import android.content.IContentProvider;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;
import android.util.Pair;

import java.util.Locale;

import eu.darken.porter.common.UserServiceLaunch;
import moe.shizuku.api.BinderContainer;
import moe.shizuku.starter.util.IContentProviderCompat;
import rikka.hidden.compat.ActivityManagerApis;
import rikka.shizuku.ShizukuApiConstants;
import rikka.shizuku.ShizukuProvider;
import rikka.shizuku.starter.BuildConfig;
import rikka.shizuku.server.UserService;

public class ServiceStarter {

    private static final String TAG = "ShizukuServiceStarter";

    private static final String EXTRA_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER";

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
            "--nice-name='%s' moe.shizuku.starter.ServiceStarter " +
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
     * Asks the server whether {@code token} still belongs to a live record, through the manager's
     * provider. Fails closed: a null binder means the manager has not received one yet, and the
     * attach that follows would fail on that too.
     */
    private static boolean isLaunchTokenLive(String token) {
        if (token == null) {
            Log.e(TAG, "no --token= to validate");
            return false;
        }
        String name = managerPackageName + ".porter";
        int userId = 0;
        IContentProvider provider = null;
        try {
            provider = ActivityManagerApis.getContentProviderExternal(name, userId, null, name);
            if (provider == null) {
                Log.e(TAG, String.format("provider is null %s %d", name, userId));
                return false;
            }
            // getBinder hands back the server binder without attaching, and unlike sendUserService it
            // does not wait for the manager's state machine. Both layers reject null extras.
            Bundle reply = IContentProviderCompat.call(provider, null, null, name,
                    ShizukuProvider.METHOD_GET_BINDER, null, new Bundle());
            if (reply == null) {
                Log.e(TAG, "no server binder to validate against");
                return false;
            }
            reply.setClassLoader(BinderContainer.class.getClassLoader());
            BinderContainer container = reply.getParcelable(EXTRA_BINDER);
            if (container == null || container.binder == null || !container.binder.pingBinder()) {
                Log.e(TAG, "no server binder to validate against");
                return false;
            }
            return queryTokenLive(container.binder, token);
        } catch (Throwable tr) {
            Log.e(TAG, "failed to validate user service token", tr);
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

    private static boolean queryTokenLive(IBinder binder, String token) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ShizukuApiConstants.BINDER_DESCRIPTOR);
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
        String name = managerPackageName + ".porter";
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
            extra.putParcelable(EXTRA_BINDER, new BinderContainer(binder));
            extra.putString(ShizukuApiConstants.USER_SERVICE_ARG_TOKEN, token);

            Bundle reply = IContentProviderCompat.call(provider, null, null, name, "sendUserService", null, extra);

            if (reply != null) {
                reply.setClassLoader(BinderContainer.class.getClassLoader());

                Log.i(TAG, String.format("send binder to %s in user %d", managerPackageName, userId));
                BinderContainer container = reply.getParcelable(EXTRA_BINDER);

                if (container != null && container.binder != null && container.binder.pingBinder()) {
                    shizukuBinder = container.binder;
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
