package eu.darken.porter.updatefixture

import android.content.pm.ApplicationInfo
import android.ddm.DdmHandleAppName
import android.os.Binder
import android.os.Bundle
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.os.RemoteException
import eu.darken.porter.common.DiscoveredApplication
import eu.darken.porter.common.GlobalAccess
import eu.darken.porter.common.PorterBuildIdentity
import eu.darken.porter.porsh.PorshConfig
import eu.darken.porter.privileged.BuildConfig
import eu.darken.porter.privileged.PorterServer
import eu.darken.porter.privileged.PorterServiceEndpoint
import eu.darken.porter.privileged.ServerConstants
import eu.darken.porter.privileged.ShizukuServiceEndpoint
import eu.darken.porter.protocol.PorterProtocol
import java.util.Locale
import rikka.parcelablelist.ParcelableListSlice
import rikka.shizuku.ShizukuApiConstants

/** Separately installed synthetic service for reproducible update failures, never packaged in Porter. */
object FixtureService {

    private lateinit var mode: String

    @JvmStatic
    fun main(args: Array<String>) {
        mode = if (args.isEmpty()) "outdated" else args[0]
        DdmHandleAppName.setAppName("porter_server", 0)
        PorshConfig.setLibraryPath(System.getProperty("porter.library.path"))
        PorshConfig.init(ShizukuApiConstants.BINDER_DESCRIPTOR, 30000)
        Looper.prepareMainLooper()
        PorterServer.bootstrap(::FixtureEndpoint, ::FixturePorterEndpoint)
        Looper.loop()
    }

    /** The diagnostics reply every mode answers with; `legacy` reports only the pid. */
    private fun writeDiagnostics(reply: Parcel) {
        reply.writeNoException()
        reply.writeInt(Process.myPid())
        if (mode != "legacy") {
            val version = Bundle()
            version.putString(ServerConstants.DIAGNOSTICS_VERSION_NAME, BuildConfig.PORTER_VERSION_NAME)
            version.putInt(ServerConstants.DIAGNOSTICS_VERSION_CODE, BuildConfig.PORTER_VERSION_CODE)
            version.putString(PorterBuildIdentity.DIAGNOSTICS_KEY, "review-fixture:debug")
            reply.writeBundle(version)
        }
    }

    private fun writeLongApplicationList(reply: Parcel) {
        val apps = ArrayList<DiscoveredApplication>()
        for (i in 1..60) {
            val info = ApplicationInfo()
            info.packageName = String.format(Locale.ROOT, "review.porter.application%02d", i)
            info.nonLocalizedLabel = String.format(Locale.ROOT, "Review application %02d", i)
            info.uid = 15000 + i
            apps.add(
                DiscoveredApplication(
                    info, 0, DiscoveredApplication.API_PORTER,
                    DiscoveredApplication.DEFAULT, DiscoveredApplication.UNSUPPORTED, false, 0,
                ),
            )
        }
        reply.writeNoException()
        reply.writeInt(DiscoveredApplication.WIRE_VERSION)
        reply.writeIntArray(IntArray(0))
        ParcelableListSlice(apps).writeToParcel(reply, 0)
    }

    private fun writeRestrictedPermission(data: Parcel, reply: Parcel) {
        val permission = data.readString()
        reply.writeNoException()
        reply.writeInt(if (permission == "android.permission.GRANT_RUNTIME_PERMISSIONS") -1 else 0)
    }

    /** Rewrites the replacement command the mode is about; other launches pass through untouched. */
    private fun rewriteLaunch(cmd: Array<String>?): Array<String>? {
        val replacing = cmd != null && cmd.any { it.startsWith("--replace=") }
        if (!replacing) return cmd
        if (mode == "preflight") {
            for (i in cmd!!.indices) if (cmd[i].startsWith("--replace=")) cmd[i] = "--replace=1"
            return cmd
        }
        if (mode == "handoff-failure") {
            return arrayOf("sh", "-c", "sleep 0.2; kill -9 " + Process.myPid())
        }
        if (mode == "slow") {
            return arrayOf("sh", "-c", "sleep 3; exec \"$@\"", "fixture") + cmd!!
        }
        return cmd
    }

    private fun interceptsLaunch(): Boolean = mode == "preflight" || mode == "handoff-failure" || mode == "slow"

    class FixtureEndpoint(service: PorterServer) : ShizukuServiceEndpoint(service) {

        @Throws(RemoteException::class)
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == ServerConstants.BINDER_TRANSACTION_getDiagnostics) {
                data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR)
                enforceManagerPermission("fixtureDiagnostics")
                writeDiagnostics(reply!!)
                return true
            }
            if (mode == "restricted" && code == DiscoveredApplication.TRANSACTION) {
                data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR)
                enforceManagerPermission("fixtureLongList")
                writeLongApplicationList(reply!!)
                return true
            }
            if (code == GlobalAccess.TRANSACTION || code == DiscoveredApplication.TRANSACTION) {
                if (mode == "legacy") return false
                if (mode == "incompatible") {
                    data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR)
                    enforceManagerPermission("fixtureCapabilities")
                    if (code == GlobalAccess.TRANSACTION && data.readInt() == GlobalAccess.WRITE) {
                        throw IllegalStateException("Manager must reject incompatible protocol before writing")
                    }
                    reply!!.writeNoException()
                    reply.writeInt(999)
                    return true
                }
            }
            // IShizukuService fixes checkPermission at transaction offset 4 and newProcess at offset 7.
            if (mode == "restricted" && code == Binder.FIRST_CALL_TRANSACTION + 4) {
                data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR)
                enforceManagerPermission("fixturePermission")
                writeRestrictedPermission(data, reply!!)
                return true
            }
            if (code == Binder.FIRST_CALL_TRANSACTION + 7 && interceptsLaunch()) {
                data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR)
                enforceManagerPermission("fixtureLaunch")
                val cmd = data.createStringArray()
                val env = data.createStringArray()
                val dir = data.readString()
                val process = super.newProcess(rewriteLaunch(cmd), env, dir)
                reply!!.writeNoException()
                reply.writeStrongBinder(process.asBinder())
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    /** The same modes on the Porter wire, which is the one the manager and the starter use. */
    class FixturePorterEndpoint(service: PorterServer) : PorterServiceEndpoint(service) {

        @Throws(RemoteException::class)
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == ServerConstants.BINDER_TRANSACTION_getDiagnostics) {
                data.enforceInterface(PorterProtocol.DESCRIPTOR)
                enforceManagerPermission("fixtureDiagnostics")
                writeDiagnostics(reply!!)
                return true
            }
            if (mode == "restricted" && code == DiscoveredApplication.TRANSACTION) {
                data.enforceInterface(PorterProtocol.DESCRIPTOR)
                enforceManagerPermission("fixtureLongList")
                writeLongApplicationList(reply!!)
                return true
            }
            if (code == GlobalAccess.TRANSACTION || code == DiscoveredApplication.TRANSACTION) {
                if (mode == "legacy") return false
                if (mode == "incompatible") {
                    data.enforceInterface(PorterProtocol.DESCRIPTOR)
                    enforceManagerPermission("fixtureCapabilities")
                    if (code == GlobalAccess.TRANSACTION && data.readInt() == GlobalAccess.WRITE) {
                        throw IllegalStateException("Manager must reject incompatible protocol before writing")
                    }
                    reply!!.writeNoException()
                    reply.writeInt(999)
                    return true
                }
            }
            // IPorterService numbers its methods explicitly: checkPermission is id 3, newProcess 7.
            if (mode == "restricted" && code == Binder.FIRST_CALL_TRANSACTION + 3) {
                data.enforceInterface(PorterProtocol.DESCRIPTOR)
                enforceManagerPermission("fixturePermission")
                writeRestrictedPermission(data, reply!!)
                return true
            }
            if (code == Binder.FIRST_CALL_TRANSACTION + 7 && interceptsLaunch()) {
                data.enforceInterface(PorterProtocol.DESCRIPTOR)
                enforceManagerPermission("fixtureLaunch")
                val cmd = data.createStringArray()
                val env = data.createStringArray()
                val dir = data.readString()
                val process = super.newProcess(rewriteLaunch(cmd), env, dir)
                reply!!.writeNoException()
                reply.writeStrongBinder(process.asBinder())
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }
}
