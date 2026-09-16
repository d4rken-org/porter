package rikka.shizuku.server;

import android.content.pm.PackageInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;

import eu.darken.porter.common.GlobalAccess;
import eu.darken.porter.core.CallerIdentity;
import moe.shizuku.common.util.OsUtils;
import rikka.parcelablelist.ParcelableListSlice;

/**
 * The app's own transaction codes, answered the same way on every endpoint. Only the descriptor
 * differs between wires, so {@code enforceInterface} stays at the call site.
 */
final class ManagerTransactions {

    private static void enforceManagerPermission(ShizukuService service, String func) {
        service.getCore().enforceManagerPermission(func, CallerIdentity.fromBinder());
    }

    static void compatibilitySetup(ShizukuService service, Parcel data, Parcel reply) {
        enforceManagerPermission(service, "compatibilitySetup");
        if (Binder.getCallingUid() / 100000 != 0)
            throw new SecurityException("Compatibility setup requires the primary Android user");
        int operation = data.readInt();
        String snapshot = data.readString();
        try {
            Bundle result = service.compatibilitySetup(operation, snapshot);
            reply.writeNoException();
            reply.writeBundle(result);
        } catch (Exception e) {
            reply.writeException(e instanceof RuntimeException ? (RuntimeException) e : new IllegalStateException(e.getMessage(), e));
        }
    }

    static void globalAccess(ShizukuService service, Parcel data, Parcel reply) {
        enforceManagerPermission(service, "globalAccess");
        int operation = data.readInt();
        if (operation == GlobalAccess.WRITE) service.setGlobalAccess(data.readInt() != 0);
        else if (operation != GlobalAccess.READ) throw new IllegalArgumentException("Unknown global access operation");
        reply.writeNoException();
        reply.writeInt(GlobalAccess.VERSION);
        reply.writeInt(service.getConfigManager().isAccessPaused() ? 0 : 1);
    }

    static void discoverApplications(ShizukuService service, Parcel data, Parcel reply) {
        enforceManagerPermission(service, "discoverApplications");
        service.writeDiscovery(data.readInt(), reply);
    }

    static void getApplications(ShizukuService service, Parcel data, Parcel reply) {
        enforceManagerPermission(service, "getApplications");
        int userId = data.readInt();
        ParcelableListSlice<PackageInfo> result = service.getApplications(userId);
        reply.writeNoException();
        result.writeToParcel(reply, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
    }

    static void setDebugLogging(ShizukuService service, Parcel data, Parcel reply) {
        enforceManagerPermission(service, "setDebugLogging");
        IBinder token = data.readStrongBinder();
        long granted = service.debugLogLeases.update(token, data.readLong());
        reply.writeNoException();
        reply.writeLong(granted);
    }

    static void getDiagnostics(ShizukuService service, Parcel data, Parcel reply) {
        enforceManagerPermission(service, "getDiagnostics");
        reply.writeNoException();
        reply.writeInt(android.os.Process.myPid());
        Bundle version = new Bundle();
        version.putString(ServerConstants.DIAGNOSTICS_VERSION_NAME, moe.shizuku.server.BuildConfig.PORTER_VERSION_NAME);
        version.putInt(ServerConstants.DIAGNOSTICS_VERSION_CODE, moe.shizuku.server.BuildConfig.PORTER_VERSION_CODE);
        version.putString(eu.darken.porter.common.PorterBuildIdentity.DIAGNOSTICS_KEY,
                eu.darken.porter.common.PorterBuildIdentity.ID + ":" + moe.shizuku.server.BuildConfig.BUILD_TYPE);
        service.reconciler.writeDiagnostics(version);
        reply.writeBundle(version);
    }

    static void userServiceLaunch(ShizukuService service, Parcel data, Parcel reply) {
        // Not enforceManagerPermission: that passes only for this pid or the manager app id, and
        // the starter is a separate process running as the server's own uid. What this proves is
        // that the caller runs as that uid and already holds the token it is asking about; it
        // does not single out one launch, and it is not the host application's uid.
        if (Binder.getCallingUid() != OsUtils.getUid()) {
            throw new SecurityException("Permission Denial: validateUserServiceToken from uid " + Binder.getCallingUid());
        }
        boolean live = service.getUserServiceManager().isUserServiceTokenLive(data.readString());
        reply.writeNoException();
        reply.writeInt(live ? 1 : 0);
    }

    private ManagerTransactions() {}
}
