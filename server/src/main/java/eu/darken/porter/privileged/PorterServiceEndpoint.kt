package eu.darken.porter.privileged

import android.os.Bundle
import android.os.Parcel
import android.os.RemoteException
import eu.darken.porter.common.CompatibilitySetup
import eu.darken.porter.common.DiscoveredApplication
import eu.darken.porter.common.GlobalAccess
import eu.darken.porter.common.UserServiceLaunch
import eu.darken.porter.core.CallerIdentity
import eu.darken.porter.endpoint.PorterEndpoint
import eu.darken.porter.protocol.PorterProtocol
import eu.darken.porter.server.IPorterApplication

/** The Porter wire as Porter answers it: the shared endpoint plus the app's own transaction codes. */
class PorterServiceEndpoint(private val service: PorterServer) : PorterEndpoint(service.core, service) {

    internal fun enforceManagerPermission(func: String) {
        service.core.enforceManagerPermission(func, CallerIdentity.fromBinder())
    }

    override fun attach(application: IPorterApplication?, args: Bundle?): Bundle {
        // The reply is the delivery here, and it is built below this call; setGlobalAccess holds
        // the same monitor across its own client notification, so a pause can never overtake it.
        synchronized(service.core.clientManager) {
            return super.attach(application, args)
        }
    }

    @Throws(RemoteException::class)
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        when (code) {
            CompatibilitySetup.TRANSACTION -> {
                data.enforceInterface(PorterProtocol.DESCRIPTOR)
                ManagerTransactions.compatibilitySetup(service, data, reply)
                return true
            }
            GlobalAccess.TRANSACTION -> {
                data.enforceInterface(PorterProtocol.DESCRIPTOR)
                ManagerTransactions.globalAccess(service, data, reply)
                return true
            }
            DiscoveredApplication.TRANSACTION -> {
                data.enforceInterface(PorterProtocol.DESCRIPTOR)
                ManagerTransactions.discoverApplications(service, data, reply)
                return true
            }
            ServerConstants.BINDER_TRANSACTION_getApplications -> {
                data.enforceInterface(PorterProtocol.DESCRIPTOR)
                ManagerTransactions.getApplications(service, data, reply)
                return true
            }
            ServerConstants.BINDER_TRANSACTION_setDebugLogging -> {
                data.enforceInterface(PorterProtocol.DESCRIPTOR)
                ManagerTransactions.setDebugLogging(service, data, reply)
                return true
            }
            ServerConstants.BINDER_TRANSACTION_getDiagnostics -> {
                data.enforceInterface(PorterProtocol.DESCRIPTOR)
                ManagerTransactions.getDiagnostics(service, data, reply)
                return true
            }
            UserServiceLaunch.TRANSACTION -> {
                data.enforceInterface(PorterProtocol.DESCRIPTOR)
                ManagerTransactions.userServiceLaunch(service, data, reply)
                return true
            }
        }
        return super.onTransact(code, data, reply, flags)
    }
}
