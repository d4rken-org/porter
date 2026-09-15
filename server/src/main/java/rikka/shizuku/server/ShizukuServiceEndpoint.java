package rikka.shizuku.server;

import android.content.pm.PackageInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import eu.darken.porter.common.DiscoveredApplication;
import eu.darken.porter.common.GlobalAccess;
import eu.darken.porter.core.CallerIdentity;
import moe.shizuku.common.util.OsUtils;
import rikka.parcelablelist.ParcelableListSlice;
import rikka.shizuku.ShizukuApiConstants;

/** The Shizuku wire as Porter answers it: the legacy endpoint plus the app's own transaction codes. */
public class ShizukuServiceEndpoint extends ShizukuLegacyEndpoint {

    private final ShizukuService service;

    public ShizukuServiceEndpoint(ShizukuService service) {
        super(service.getCore(), service);
        this.service = service;
    }

    protected final void enforceManagerPermission(String func) {
        service.getCore().enforceManagerPermission(func, CallerIdentity.fromBinder());
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        // Attach builds and delivers its reply below this call; setGlobalAccess holds the same
        // monitor across its own bindApplication, so a pause can never overtake an attach reply.
        // Raw 14 is the pre-v13 attach the legacy endpoint handles itself; 18 is
        // IShizukuService.attachApplication, whose AIDL id is 17.
        if (code == 14 || code == Binder.FIRST_CALL_TRANSACTION + 17) {
            synchronized (service.getCore().getClientManager()) {
                return super.onTransact(code, data, reply, flags);
            }
        }
        if (code == eu.darken.porter.common.CompatibilitySetup.TRANSACTION) {
            data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR);
            enforceManagerPermission("compatibilitySetup");
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
            return true;
        }
        if (code == GlobalAccess.TRANSACTION) {
            data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR);
            enforceManagerPermission("globalAccess");
            int operation = data.readInt();
            if (operation == GlobalAccess.WRITE) service.setGlobalAccess(data.readInt() != 0);
            else if (operation != GlobalAccess.READ) throw new IllegalArgumentException("Unknown global access operation");
            reply.writeNoException();
            reply.writeInt(GlobalAccess.VERSION);
            reply.writeInt(service.getConfigManager().isAccessPaused() ? 0 : 1);
            return true;
        }
        if (code == DiscoveredApplication.TRANSACTION) {
            data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR);
            enforceManagerPermission("discoverApplications");
            service.writeDiscovery(data.readInt(), reply);
            return true;
        }
        if (code == ServerConstants.BINDER_TRANSACTION_getApplications) {
            data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR);
            enforceManagerPermission("getApplications");
            int userId = data.readInt();
            ParcelableListSlice<PackageInfo> result = service.getApplications(userId);
            reply.writeNoException();
            result.writeToParcel(reply, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
            return true;
        }
        if (code == ServerConstants.BINDER_TRANSACTION_setDebugLogging) {
            data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR);
            enforceManagerPermission("setDebugLogging");
            IBinder token = data.readStrongBinder();
            long granted = service.debugLogLeases.update(token, data.readLong());
            reply.writeNoException();
            reply.writeLong(granted);
            return true;
        }
        if (code == ServerConstants.BINDER_TRANSACTION_getDiagnostics) {
            data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR);
            enforceManagerPermission("getDiagnostics");
            reply.writeNoException();
            reply.writeInt(android.os.Process.myPid());
            Bundle version = new Bundle();
            version.putString(ServerConstants.DIAGNOSTICS_VERSION_NAME, moe.shizuku.server.BuildConfig.PORTER_VERSION_NAME);
            version.putInt(ServerConstants.DIAGNOSTICS_VERSION_CODE, moe.shizuku.server.BuildConfig.PORTER_VERSION_CODE);
            version.putString(eu.darken.porter.common.PorterBuildIdentity.DIAGNOSTICS_KEY,
                    eu.darken.porter.common.PorterBuildIdentity.ID + ":" + moe.shizuku.server.BuildConfig.BUILD_TYPE);
            service.reconciler.writeDiagnostics(version);
            reply.writeBundle(version);
            return true;
        }
        if (code == eu.darken.porter.common.UserServiceLaunch.TRANSACTION) {
            data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR);
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
            return true;
        }
        return super.onTransact(code, data, reply, flags);
    }
}
