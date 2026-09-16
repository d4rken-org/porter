package rikka.shizuku.server;

import android.os.Bundle;
import android.os.Parcel;
import android.os.RemoteException;

import eu.darken.porter.common.CompatibilitySetup;
import eu.darken.porter.common.DiscoveredApplication;
import eu.darken.porter.common.GlobalAccess;
import eu.darken.porter.common.UserServiceLaunch;
import eu.darken.porter.endpoint.PorterEndpoint;
import eu.darken.porter.protocol.PorterProtocol;
import eu.darken.porter.server.IPorterApplication;

/** The Porter wire as Porter answers it: the shared endpoint plus the app's own transaction codes. */
public class PorterServiceEndpoint extends PorterEndpoint {

    private final ShizukuService service;

    public PorterServiceEndpoint(ShizukuService service) {
        super(service.getCore(), service);
        this.service = service;
    }

    @Override
    public Bundle attach(IPorterApplication application, Bundle args) {
        // The reply is the delivery here, and it is built below this call; setGlobalAccess holds
        // the same monitor across its own client notification, so a pause can never overtake it.
        synchronized (service.getCore().getClientManager()) {
            return super.attach(application, args);
        }
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == CompatibilitySetup.TRANSACTION) {
            data.enforceInterface(PorterProtocol.DESCRIPTOR);
            ManagerTransactions.compatibilitySetup(service, data, reply);
            return true;
        }
        if (code == GlobalAccess.TRANSACTION) {
            data.enforceInterface(PorterProtocol.DESCRIPTOR);
            ManagerTransactions.globalAccess(service, data, reply);
            return true;
        }
        if (code == DiscoveredApplication.TRANSACTION) {
            data.enforceInterface(PorterProtocol.DESCRIPTOR);
            ManagerTransactions.discoverApplications(service, data, reply);
            return true;
        }
        if (code == ServerConstants.BINDER_TRANSACTION_getApplications) {
            data.enforceInterface(PorterProtocol.DESCRIPTOR);
            ManagerTransactions.getApplications(service, data, reply);
            return true;
        }
        if (code == ServerConstants.BINDER_TRANSACTION_setDebugLogging) {
            data.enforceInterface(PorterProtocol.DESCRIPTOR);
            ManagerTransactions.setDebugLogging(service, data, reply);
            return true;
        }
        if (code == ServerConstants.BINDER_TRANSACTION_getDiagnostics) {
            data.enforceInterface(PorterProtocol.DESCRIPTOR);
            ManagerTransactions.getDiagnostics(service, data, reply);
            return true;
        }
        if (code == UserServiceLaunch.TRANSACTION) {
            data.enforceInterface(PorterProtocol.DESCRIPTOR);
            ManagerTransactions.userServiceLaunch(service, data, reply);
            return true;
        }
        return super.onTransact(code, data, reply, flags);
    }
}
