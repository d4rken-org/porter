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
import eu.darken.porter.protocol.PorterProtocol;
import eu.darken.porter.server.IPorterRemoteProcess;
import moe.shizuku.server.IRemoteProcess;
import eu.darken.porter.porsh.PorshConfig;
import rikka.shizuku.ShizukuApiConstants;
import eu.darken.porter.privileged.PorterServiceEndpoint;
import eu.darken.porter.privileged.ServerConstants;
import eu.darken.porter.privileged.PorterServer;
import eu.darken.porter.privileged.ShizukuServiceEndpoint;

/** Separately installed synthetic service for reproducible update failures, never packaged in Porter. */
public final class FixtureService {
    private static String mode;

    public static void main(String[] args) {
        mode = args.length == 0 ? "outdated" : args[0];
        DdmHandleAppName.setAppName("porter_server", 0);
        PorshConfig.setLibraryPath(System.getProperty("porter.library.path"));
        PorshConfig.init(ShizukuApiConstants.BINDER_DESCRIPTOR, 30000);
        Looper.prepareMainLooper();
        PorterServer.bootstrap(FixtureEndpoint::new, FixturePorterEndpoint::new);
        Looper.loop();
    }

    /** The diagnostics reply every mode answers with; {@code legacy} reports only the pid. */
    private static void writeDiagnostics(Parcel reply) {
        reply.writeNoException();
        reply.writeInt(android.os.Process.myPid());
        if (!mode.equals("legacy")) {
            Bundle version = new Bundle();
            version.putString(ServerConstants.DIAGNOSTICS_VERSION_NAME, eu.darken.porter.privileged.BuildConfig.PORTER_VERSION_NAME);
            version.putInt(ServerConstants.DIAGNOSTICS_VERSION_CODE, eu.darken.porter.privileged.BuildConfig.PORTER_VERSION_CODE);
            version.putString(PorterBuildIdentity.DIAGNOSTICS_KEY, "review-fixture:debug");
            reply.writeBundle(version);
        }
    }

    private static void writeLongApplicationList(Parcel reply) {
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
    }

    private static void writeRestrictedPermission(Parcel data, Parcel reply) {
        String permission = data.readString();
        reply.writeNoException();
        reply.writeInt(permission.equals("android.permission.GRANT_RUNTIME_PERMISSIONS") ? -1 : 0);
    }

    /** Rewrites the replacement command the mode is about; other launches pass through untouched. */
    private static String[] rewriteLaunch(String[] cmd) {
        boolean replacing = cmd != null && java.util.Arrays.stream(cmd).anyMatch(it -> it.startsWith("--replace="));
        if (!replacing) return cmd;
        if (mode.equals("preflight")) {
            for (int i = 0; i < cmd.length; i++) if (cmd[i].startsWith("--replace=")) cmd[i] = "--replace=1";
            return cmd;
        }
        if (mode.equals("handoff-failure")) {
            return new String[]{"sh", "-c", "sleep 0.2; kill -9 " + android.os.Process.myPid()};
        }
        if (mode.equals("slow")) {
            String[] wrapped = new String[cmd.length + 4];
            wrapped[0] = "sh"; wrapped[1] = "-c";
            wrapped[2] = "sleep 3; exec \"$@\""; wrapped[3] = "fixture";
            System.arraycopy(cmd, 0, wrapped, 4, cmd.length);
            return wrapped;
        }
        return cmd;
    }

    private static boolean interceptsLaunch() {
        return mode.equals("preflight") || mode.equals("handoff-failure") || mode.equals("slow");
    }

    public static final class FixtureEndpoint extends ShizukuServiceEndpoint {

        public FixtureEndpoint(PorterServer service) {
            super(service);
        }

        @Override public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == ServerConstants.BINDER_TRANSACTION_getDiagnostics) {
                data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR);
                enforceManagerPermission("fixtureDiagnostics");
                writeDiagnostics(reply);
                return true;
            }
            if (mode.equals("restricted") && code == DiscoveredApplication.TRANSACTION) {
                data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR);
                enforceManagerPermission("fixtureLongList");
                writeLongApplicationList(reply);
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
            // IShizukuService fixes checkPermission at transaction offset 4 and newProcess at offset 7.
            if (mode.equals("restricted") && code == Binder.FIRST_CALL_TRANSACTION + 4) {
                data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR);
                enforceManagerPermission("fixturePermission");
                writeRestrictedPermission(data, reply);
                return true;
            }
            if (code == Binder.FIRST_CALL_TRANSACTION + 7 && interceptsLaunch()) {
                data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR);
                enforceManagerPermission("fixtureLaunch");
                String[] cmd = data.createStringArray();
                String[] env = data.createStringArray();
                String dir = data.readString();
                IRemoteProcess process = super.newProcess(rewriteLaunch(cmd), env, dir);
                reply.writeNoException();
                reply.writeStrongBinder(process.asBinder());
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }

    /** The same modes on the Porter wire, which is the one the manager and the starter use. */
    public static final class FixturePorterEndpoint extends PorterServiceEndpoint {

        public FixturePorterEndpoint(PorterServer service) {
            super(service);
        }

        @Override public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == ServerConstants.BINDER_TRANSACTION_getDiagnostics) {
                data.enforceInterface(PorterProtocol.DESCRIPTOR);
                enforceManagerPermission("fixtureDiagnostics");
                writeDiagnostics(reply);
                return true;
            }
            if (mode.equals("restricted") && code == DiscoveredApplication.TRANSACTION) {
                data.enforceInterface(PorterProtocol.DESCRIPTOR);
                enforceManagerPermission("fixtureLongList");
                writeLongApplicationList(reply);
                return true;
            }
            if (code == GlobalAccess.TRANSACTION || code == DiscoveredApplication.TRANSACTION) {
                if (mode.equals("legacy")) return false;
                if (mode.equals("incompatible")) {
                    data.enforceInterface(PorterProtocol.DESCRIPTOR);
                    enforceManagerPermission("fixtureCapabilities");
                    if (code == GlobalAccess.TRANSACTION && data.readInt() == GlobalAccess.WRITE)
                        throw new IllegalStateException("Manager must reject incompatible protocol before writing");
                    reply.writeNoException();
                    reply.writeInt(999);
                    return true;
                }
            }
            // IPorterService numbers its methods explicitly: checkPermission is id 3, newProcess 7.
            if (mode.equals("restricted") && code == Binder.FIRST_CALL_TRANSACTION + 3) {
                data.enforceInterface(PorterProtocol.DESCRIPTOR);
                enforceManagerPermission("fixturePermission");
                writeRestrictedPermission(data, reply);
                return true;
            }
            if (code == Binder.FIRST_CALL_TRANSACTION + 7 && interceptsLaunch()) {
                data.enforceInterface(PorterProtocol.DESCRIPTOR);
                enforceManagerPermission("fixtureLaunch");
                String[] cmd = data.createStringArray();
                String[] env = data.createStringArray();
                String dir = data.readString();
                IPorterRemoteProcess process = super.newProcess(rewriteLaunch(cmd), env, dir);
                reply.writeNoException();
                reply.writeStrongBinder(process.asBinder());
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }
}
