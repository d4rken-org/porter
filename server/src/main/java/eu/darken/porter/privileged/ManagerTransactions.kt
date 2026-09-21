package eu.darken.porter.privileged

import android.content.pm.PackageInfo
import android.os.Binder
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.os.Process
import eu.darken.porter.common.GlobalAccess
import eu.darken.porter.common.PorterBuildIdentity
import eu.darken.porter.common.util.OsUtils
import eu.darken.porter.core.CallerIdentity
import rikka.parcelablelist.ParcelableListSlice

/**
 * The app's own transaction codes, answered the same way on every endpoint. Only the descriptor
 * differs between wires, so `enforceInterface` stays at the call site.
 */
internal object ManagerTransactions {

    private fun enforceManagerPermission(service: PorterServer, func: String) {
        service.core.enforceManagerPermission(func, CallerIdentity.fromBinder())
    }

    /** The app's codes all answer, so a one-way delivery of one is a caller error. */
    private fun requireReply(reply: Parcel?): Parcel = reply ?: throw NullPointerException("reply is null")

    fun compatibilitySetup(service: PorterServer, data: Parcel, reply: Parcel?) {
        val out = requireReply(reply)
        enforceManagerPermission(service, "compatibilitySetup")
        if (Binder.getCallingUid() / 100000 != 0) {
            throw SecurityException("Compatibility setup requires the primary Android user")
        }
        val operation = data.readInt()
        val snapshot = data.readString()
        try {
            val result = service.compatibilitySetup(operation, snapshot)
            out.writeNoException()
            out.writeBundle(result)
        } catch (e: Exception) {
            out.writeException(e as? RuntimeException ?: IllegalStateException(e.message, e))
        }
    }

    fun globalAccess(service: PorterServer, data: Parcel, reply: Parcel?) {
        val out = requireReply(reply)
        enforceManagerPermission(service, "globalAccess")
        val operation = data.readInt()
        if (operation == GlobalAccess.WRITE) {
            service.setGlobalAccess(data.readInt() != 0)
        } else if (operation != GlobalAccess.READ) {
            throw IllegalArgumentException("Unknown global access operation")
        }
        out.writeNoException()
        out.writeInt(GlobalAccess.VERSION)
        out.writeInt(if (service.configManager.isAccessPaused) 0 else 1)
    }

    fun discoverApplications(service: PorterServer, data: Parcel, reply: Parcel?) {
        val out = requireReply(reply)
        enforceManagerPermission(service, "discoverApplications")
        service.writeDiscovery(data.readInt(), out)
    }

    fun getApplications(service: PorterServer, data: Parcel, reply: Parcel?) {
        val out = requireReply(reply)
        enforceManagerPermission(service, "getApplications")
        val userId = data.readInt()
        val result: ParcelableListSlice<PackageInfo> = service.getApplications(userId)
        out.writeNoException()
        result.writeToParcel(out, Parcelable.PARCELABLE_WRITE_RETURN_VALUE)
    }

    fun setDebugLogging(service: PorterServer, data: Parcel, reply: Parcel?) {
        val out = requireReply(reply)
        enforceManagerPermission(service, "setDebugLogging")
        val token = data.readStrongBinder()
        val granted = service.debugLogLeases.update(token, data.readLong())
        out.writeNoException()
        out.writeLong(granted)
    }

    fun getDiagnostics(service: PorterServer, data: Parcel, reply: Parcel?) {
        val out = requireReply(reply)
        enforceManagerPermission(service, "getDiagnostics")
        out.writeNoException()
        out.writeInt(Process.myPid())
        val version = Bundle()
        version.putString(ServerConstants.DIAGNOSTICS_VERSION_NAME, BuildConfig.PORTER_VERSION_NAME)
        version.putInt(ServerConstants.DIAGNOSTICS_VERSION_CODE, BuildConfig.PORTER_VERSION_CODE)
        version.putString(PorterBuildIdentity.DIAGNOSTICS_KEY, PorterBuildIdentity.ID + ":" + BuildConfig.BUILD_TYPE)
        service.reconciler.writeDiagnostics(version)
        out.writeBundle(version)
    }

    fun userServiceLaunch(service: PorterServer, data: Parcel, reply: Parcel?) {
        val out = requireReply(reply)
        // Not enforceManagerPermission: that passes only for this pid or the manager app id, and
        // the starter is a separate process running as the server's own uid. What this proves is
        // that the caller runs as that uid and already holds the token it is asking about; it
        // does not single out one launch, and it is not the host application's uid.
        if (Binder.getCallingUid() != OsUtils.getUid()) {
            throw SecurityException("Permission Denial: validateUserServiceToken from uid " + Binder.getCallingUid())
        }
        val live = service.userServiceManager.isUserServiceTokenLive(data.readString())
        out.writeNoException()
        out.writeInt(if (live) 1 else 0)
    }
}
