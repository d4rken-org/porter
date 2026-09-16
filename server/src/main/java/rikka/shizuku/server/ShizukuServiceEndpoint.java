package rikka.shizuku.server;

import android.os.Binder;
import android.os.Parcel;
import android.os.RemoteException;

import eu.darken.porter.common.CompatibilitySetup;
import eu.darken.porter.common.DiscoveredApplication;
import eu.darken.porter.common.GlobalAccess;
import eu.darken.porter.common.UserServiceLaunch;
import eu.darken.porter.core.CallerIdentity;
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
        if (code == CompatibilitySetup.TRANSACTION) {
            data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR);
            ManagerTransactions.compatibilitySetup(service, data, reply);
            return true;
        }
        if (code == GlobalAccess.TRANSACTION) {
            data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR);
            ManagerTransactions.globalAccess(service, data, reply);
            return true;
        }
        if (code == DiscoveredApplication.TRANSACTION) {
            data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR);
            ManagerTransactions.discoverApplications(service, data, reply);
            return true;
        }
        if (code == ServerConstants.BINDER_TRANSACTION_getApplications) {
            data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR);
            ManagerTransactions.getApplications(service, data, reply);
            return true;
        }
        if (code == ServerConstants.BINDER_TRANSACTION_setDebugLogging) {
            data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR);
            ManagerTransactions.setDebugLogging(service, data, reply);
            return true;
        }
        if (code == ServerConstants.BINDER_TRANSACTION_getDiagnostics) {
            data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR);
            ManagerTransactions.getDiagnostics(service, data, reply);
            return true;
        }
        if (code == UserServiceLaunch.TRANSACTION) {
            data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR);
            ManagerTransactions.userServiceLaunch(service, data, reply);
            return true;
        }
        return super.onTransact(code, data, reply, flags);
    }
}
