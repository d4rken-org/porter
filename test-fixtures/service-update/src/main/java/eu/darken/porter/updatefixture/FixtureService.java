package eu.darken.porter.updatefixture;

import android.os.Binder;
import android.os.Bundle;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.ddm.DdmHandleAppName;
import eu.darken.porter.common.DiscoveredApplication;
import eu.darken.porter.common.GlobalAccess;
import eu.darken.porter.common.PorterBuildIdentity;
import moe.shizuku.server.IRemoteProcess;
import rikka.rish.RishConfig;
import rikka.shizuku.ShizukuApiConstants;
import rikka.shizuku.server.ServerConstants;
import rikka.shizuku.server.ShizukuService;

/** Separately installed synthetic service for reproducible update failures, never packaged in Porter. */
public final class FixtureService extends ShizukuService {
    private static String mode;

    public static void main(String[] args) {
        mode = args.length == 0 ? "outdated" : args[0];
        DdmHandleAppName.setAppName("porter_server", 0);
        RishConfig.setLibraryPath(System.getProperty("shizuku.library.path"));
        Looper.prepareMainLooper();
        new FixtureService();
        Looper.loop();
    }

    @Override public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == ServerConstants.BINDER_TRANSACTION_getDiagnostics) {
            data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR);
            enforceManagerPermission("fixtureDiagnostics");
            reply.writeNoException();
            reply.writeInt(android.os.Process.myPid());
            if (!mode.equals("legacy")) {
                Bundle version = new Bundle();
                version.putString(ServerConstants.DIAGNOSTICS_VERSION_NAME, moe.shizuku.server.BuildConfig.PORTER_VERSION_NAME);
                version.putInt(ServerConstants.DIAGNOSTICS_VERSION_CODE, moe.shizuku.server.BuildConfig.PORTER_VERSION_CODE);
                version.putString(PorterBuildIdentity.DIAGNOSTICS_KEY, "review-fixture:debug");
                reply.writeBundle(version);
            }
            return true;
        }
        if (mode.equals("restricted") && code == DiscoveredApplication.TRANSACTION) {
            data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR);
            enforceManagerPermission("fixtureLongList");
            java.util.List<DiscoveredApplication> apps = new java.util.ArrayList<>();
            for (int i = 1; i <= 60; i++) {
                android.content.pm.ApplicationInfo info = new android.content.pm.ApplicationInfo();
                info.packageName = String.format(java.util.Locale.ROOT, "review.porter.application%02d", i);
                info.nonLocalizedLabel = String.format(java.util.Locale.ROOT, "Review application %02d", i);
                info.uid = 15000 + i;
                apps.add(new DiscoveredApplication(info, 0, DiscoveredApplication.API_PORTER,
                        DiscoveredApplication.DEFAULT, DiscoveredApplication.UNSUPPORTED, false, 0));
            }
            reply.writeNoException();
            reply.writeInt(DiscoveredApplication.WIRE_VERSION);
            reply.writeIntArray(new int[0]);
            new rikka.parcelablelist.ParcelableListSlice<>(apps).writeToParcel(reply, 0);
            return true;
        }
        if (code == GlobalAccess.TRANSACTION || code == DiscoveredApplication.TRANSACTION) {
            if (mode.equals("legacy")) return false;
            if (mode.equals("incompatible")) {
                data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR);
                enforceManagerPermission("fixtureCapabilities");
                if (code == GlobalAccess.TRANSACTION && data.readInt() == GlobalAccess.WRITE)
                    throw new IllegalStateException("Manager must reject incompatible protocol before writing");
                reply.writeNoException();
                reply.writeInt(999);
                return true;
            }
        }
        // The public AIDL fixes checkPermission at transaction offset 4 and newProcess at offset 7.
        if (mode.equals("restricted") && code == Binder.FIRST_CALL_TRANSACTION + 4) {
            data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR);
            enforceManagerPermission("fixturePermission");
            String permission = data.readString();
            reply.writeNoException();
            reply.writeInt(permission.equals("android.permission.GRANT_RUNTIME_PERMISSIONS") ? -1 : 0);
            return true;
        }
        if (code == Binder.FIRST_CALL_TRANSACTION + 7 &&
                (mode.equals("preflight") || mode.equals("handoff-failure") || mode.equals("slow"))) {
            data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR);
            enforceManagerPermission("fixtureLaunch");
            String[] cmd = data.createStringArray();
            String[] env = data.createStringArray();
            String dir = data.readString();
            boolean replacing = cmd != null && java.util.Arrays.stream(cmd).anyMatch(it -> it.startsWith("--replace="));
            if (replacing && mode.equals("preflight")) {
                for (int i = 0; i < cmd.length; i++) if (cmd[i].startsWith("--replace=")) cmd[i] = "--replace=1";
            } else if (replacing && mode.equals("handoff-failure")) {
                cmd = new String[]{"sh", "-c", "sleep 0.2; kill -9 " + android.os.Process.myPid()};
            } else if (replacing && mode.equals("slow")) {
                String[] wrapped = new String[cmd.length + 4];
                wrapped[0] = "sh"; wrapped[1] = "-c";
                wrapped[2] = "sleep 3; exec \"$@\""; wrapped[3] = "fixture";
                System.arraycopy(cmd, 0, wrapped, 4, cmd.length);
                cmd = wrapped;
            }
            IRemoteProcess process = super.newProcess(cmd, env, dir);
            reply.writeNoException();
            reply.writeStrongBinder(process.asBinder());
            return true;
        }
        return super.onTransact(code, data, reply, flags);
    }
}
