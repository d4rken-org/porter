package eu.darken.porter.privileged

import android.os.Bundle
import android.os.Parcel
import android.os.RemoteException
import eu.darken.porter.common.AppTransactions
import eu.darken.porter.core.CallerIdentity
import eu.darken.porter.endpoint.PorterEndpoint
import eu.darken.porter.protocol.PorterProtocol
import eu.darken.porter.server.IPorterApplication

/** The Porter wire as Porter answers it: the shared endpoint plus the app's own transaction codes. */
open class PorterServiceEndpoint(private val service: PorterServer) : PorterEndpoint(service.core) {

    protected fun enforceManagerPermission(func: String) {
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
    open override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        when (code) {
            AppTransactions.GET_MANAGER -> {
                data.enforceInterface(PorterProtocol.DESCRIPTOR)
                ManagerTransactions.getManager(service, data, reply)
                return true
            }
            AppTransactions.COMPATIBILITY_SETUP -> {
                data.enforceInterface(PorterProtocol.DESCRIPTOR)
                ManagerTransactions.compatibilitySetup(service, data, reply)
                return true
            }
            AppTransactions.GLOBAL_ACCESS -> {
                data.enforceInterface(PorterProtocol.DESCRIPTOR)
                ManagerTransactions.globalAccess(service, data, reply)
                return true
            }
            AppTransactions.DISCOVER_APPLICATIONS -> {
                data.enforceInterface(PorterProtocol.DESCRIPTOR)
                ManagerTransactions.discoverApplications(service, data, reply)
                return true
            }
            AppTransactions.GET_APPLICATIONS -> {
                data.enforceInterface(PorterProtocol.DESCRIPTOR)
                ManagerTransactions.getApplications(service, data, reply)
                return true
            }
            AppTransactions.SET_DEBUG_LOGGING -> {
                data.enforceInterface(PorterProtocol.DESCRIPTOR)
                ManagerTransactions.setDebugLogging(service, data, reply)
                return true
            }
            AppTransactions.GET_DIAGNOSTICS -> {
                data.enforceInterface(PorterProtocol.DESCRIPTOR)
                ManagerTransactions.getDiagnostics(service, data, reply)
                return true
            }
            AppTransactions.USER_SERVICE_LAUNCH -> {
                data.enforceInterface(PorterProtocol.DESCRIPTOR)
                ManagerTransactions.userServiceLaunch(service, data, reply)
                return true
            }
        }
        return super.onTransact(code, data, reply, flags)
    }
}
