package moe.shizuku.manager.authorization

import android.content.Context
import android.content.pm.PackageInfo
import android.os.IBinder
import eu.darken.porter.common.DiscoveredApplication
import eu.darken.porter.common.GlobalAccess
import android.os.Parcel
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.Manifest
import eu.darken.porter.privileged.ServerConstants
import rikka.parcelablelist.ParcelableListSlice
import eu.darken.porter.protocol.PorterProtocol
import eu.darken.porter.sdk.Porter
import java.util.*

object AuthorizationManager {

    private const val FLAG_ALLOWED = 1 shl 1
    private const val FLAG_DENIED = 1 shl 2
    private const val MASK_PERMISSION = FLAG_ALLOWED or FLAG_DENIED

    private fun getApplications(userId: Int): List<PackageInfo> {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(PorterProtocol.DESCRIPTOR)
            data.writeInt(userId)
            try {
                Porter.getBinder()!!.transact(ServerConstants.BINDER_TRANSACTION_getApplications, data, reply, 0)
            } catch (e: Throwable) {
                throw RuntimeException(e)
            }
            reply.readException()
            @Suppress("UNCHECKED_CAST")
            (ParcelableListSlice.CREATOR.createFromParcel(reply) as ParcelableListSlice<PackageInfo>).list!!
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    data class Discovery(val apps: List<DiscoveredApplication>, val failedUsers: List<Int> = emptyList(), val legacy: Boolean = false)

    internal fun readDiscovery(binder: IBinder): Discovery? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(PorterProtocol.DESCRIPTOR)
            data.writeInt(-1)
            if (!binder.transact(DiscoveredApplication.TRANSACTION, data, reply, 0)) return null
            reply.readException()
            if (reply.readInt() != DiscoveredApplication.WIRE_VERSION) return null
            val failedUsers = reply.createIntArray()?.toList().orEmpty()
            @Suppress("UNCHECKED_CAST")
            val apps = (ParcelableListSlice.CREATOR.createFromParcel(reply) as ParcelableListSlice<DiscoveredApplication>).list.orEmpty()
            return Discovery(apps, failedUsers)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    internal fun globalAccess(binder: IBinder, enabled: Boolean? = null): Boolean? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(PorterProtocol.DESCRIPTOR)
            data.writeInt(if (enabled == null) GlobalAccess.READ else GlobalAccess.WRITE)
            enabled?.let { data.writeInt(if (it) 1 else 0) }
            if (!binder.transact(GlobalAccess.TRANSACTION, data, reply, 0)) return null
            reply.readException()
            if (reply.readInt() != GlobalAccess.VERSION) return null
            reply.readInt() != 0
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun getGlobalAccess(): Boolean? = globalAccess(Porter.getBinder() ?: error("Porter is not running"))
    fun setGlobalAccess(enabled: Boolean) {
        setGlobalAccess(Porter.getBinder() ?: error("Porter is not running"), enabled)
    }

    internal fun setGlobalAccess(binder: IBinder, enabled: Boolean) {
        check(globalAccess(binder) != null) { "Update the Porter service to use global access control." }
        check(globalAccess(binder, enabled) == enabled) {
            "Update the Porter service to use global access control."
        }
    }

    fun discover(): Discovery {
        val binder = Porter.getBinder() ?: throw IllegalStateException("Porter is not running")
        readDiscovery(binder)?.let { return it }
        val apps = getPackages().mapNotNull { info ->
            val ai = info.applicationInfo ?: return@mapNotNull null
            val declaredApis = (if (info.requestedPermissions?.contains(Manifest.permission.API_V23) == true) DiscoveredApplication.API_PORTER else 0) or
                (if (info.requestedPermissions?.contains(ServerConstants.LEGACY_PERMISSION) == true) DiscoveredApplication.API_SHIZUKU else 0)
            DiscoveredApplication(ai, ai.uid / 100000, declaredApis,
                if (granted(info.packageName, ai.uid)) DiscoveredApplication.ALLOWED else DiscoveredApplication.DEFAULT,
                DiscoveredApplication.UNKNOWN, ai.metaData?.getBoolean("moe.shizuku.client.V3_REQUIRES_ROOT") == true, 0)
        }
        return Discovery(apps, legacy = true)
    }

    fun getPackages(): List<PackageInfo> = getApplications(-1)

    fun granted(packageName: String, uid: Int): Boolean {
        return (Porter.getFlagsForUid(uid, MASK_PERMISSION) and FLAG_ALLOWED) == FLAG_ALLOWED
    }

    fun grant(packageName: String, uid: Int) {
        Porter.updateFlagsForUid(uid, MASK_PERMISSION, FLAG_ALLOWED)
    }

    fun revoke(packageName: String, uid: Int) {
        Porter.updateFlagsForUid(uid, MASK_PERMISSION, 0)
    }
}
