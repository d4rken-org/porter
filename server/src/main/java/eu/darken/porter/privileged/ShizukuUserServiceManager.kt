package eu.darken.porter.privileged

import android.content.pm.PackageInfo
import android.util.ArrayMap
import eu.darken.porter.privileged.util.Android17Compat
import eu.darken.porter.privileged.util.PackageIdentity
import eu.darken.porter.starter.ServiceStarter
import java.io.File
import rikka.shizuku.server.UserServiceManager
import rikka.shizuku.server.UserServiceRecord
import rikka.shizuku.server.util.UserHandleCompat

class ShizukuUserServiceManager(
    /** The manager APK a host is started from, given the installation the server started with. */
    private val managerApk: (baseline: PackageIdentity.Identity) -> String = { verifiedManagerApk(it) },
) : UserServiceManager() {

    /**
     * The identity each live record was created for. Membership is the liveness test, and nothing is
     * ever learned or updated after creation: a record either belongs to the installation that asked
     * for it or it does not.
     */
    private val hostIdentities: MutableMap<UserServiceRecord, PackageIdentity.Identity> = ArrayMap()

    @Volatile
    private var accessPaused = false

    /**
     * Set once during construction. The manager needs the reconciler to bring the next host scan
     * forward on record creation and the reconciler needs the manager for its snapshot, so one of
     * them has to be wired after the other exists. Not null-guarded: a null here would be a
     * construction-order bug worth failing on, not a deadline worth skipping.
     */
    @Volatile
    private lateinit var reconciler: ApkReconciler

    /** One live record and the identity it was created for. */
    class HostSnapshot internal constructor(val record: UserServiceRecord, val identity: PackageIdentity.Identity) {
        val packageName: String = identity.packageName
    }

    fun setReconciler(reconciler: ApkReconciler) {
        this.reconciler = reconciler
    }

    override fun getUserServiceStartCmd(
        record: UserServiceRecord,
        key: String,
        token: String,
        packageName: String,
        classname: String,
        processNameSuffix: String?,
        callingUid: Int,
        use32Bits: Boolean,
        debug: Boolean,
    ): String {
        if (accessPaused) throw SecurityException("App access is paused")
        val identity = synchronized(this) { hostIdentities[record] }
            ?: throw SecurityException("No recorded identity for " + packageName)
        var appProcess = "/system/bin/app_process"
        if (use32Bits && File("/system/bin/app_process32").exists()) {
            appProcess = "/system/bin/app_process32"
        }
        return ServiceStarter.commandForUserService(
            appProcess,
            managerApk(reconciler.managerBaseline),
            PorterServer.MANAGER_APPLICATION_ID,
            token, packageName, classname, processNameSuffix, callingUid, identity.signerDigests, debug,
        )
    }

    @Synchronized
    fun setAccessPaused(paused: Boolean) {
        accessPaused = paused
        if (paused) {
            for (record in ArrayList(hostIdentities.keys)) record.removeSelf()
        }
    }

    @Synchronized
    override fun canReuseUserServiceRecord(record: UserServiceRecord, packageInfo: PackageInfo): Boolean {
        // An unidentified record and an unreadable identity are both "cannot prove a match", which is
        // not the same as a match.
        val recorded = hostIdentities[record] ?: return false
        // The caller's user, from the lookup that was made for it: the components compared here are
        // package-global, but an observation labelled with the wrong user reads as one.
        val userId = UserHandleCompat.getUserId(packageInfo.applicationInfo!!.uid)
        return recorded.matches(PackageIdentity.observe(packageInfo, userId))
    }

    @Synchronized
    override fun onUserServiceRecordCreated(record: UserServiceRecord, packageInfo: PackageInfo) {
        if (accessPaused) {
            record.removeSelf()
            throw SecurityException("App access is paused")
        }
        super.onUserServiceRecordCreated(record, packageInfo)

        // From the PackageInfo that authorised this bind: a second lookup here would run under the
        // monitor on a binder thread with the record half registered.
        val identity = PackageIdentity.identityOf(packageInfo)
        if (identity == null) {
            // Every record in the map has an identity every later bind and every scan is judged
            // against, so a bind that cannot be identified is a bind that cannot be authorised.
            record.removeSelf()
            throw SecurityException("Cannot identify " + packageInfo.packageName)
        }
        hostIdentities[record] = identity
        reconciler.onHostRecordCreated()
    }

    @Synchronized
    override fun onUserServiceRecordRemoved(record: UserServiceRecord) {
        super.onUserServiceRecordRemoved(record)
        hostIdentities.remove(record)
    }

    @Synchronized
    fun snapshotHosts(): List<HostSnapshot> {
        val snapshot = ArrayList<HostSnapshot>(hostIdentities.size)
        for ((record, identity) in hostIdentities) {
            snapshot.add(HostSnapshot(record, identity))
        }
        return snapshot
    }

    /** Reentrant with [UserServiceRecord.removeSelf], which takes this same monitor. */
    @Synchronized
    fun removeIfPresent(record: UserServiceRecord): Boolean {
        if (!hostIdentities.containsKey(record)) return false
        record.removeSelf()
        return true
    }

    companion object {

        /**
         * The manager's APK, checked against [baseline] first. The reconciler notices a replaced
         * manager only on its next scan, and the host runs this APK's code as the server's uid.
         */
        internal fun verifiedManagerApk(baseline: PackageIdentity.Identity): String {
            val info = Android17Compat.getPackageInfoOrThrow(
                PorterServer.MANAGER_APPLICATION_ID, PackageIdentity.lookupFlags(), PorterServer.MANAGER_USER_ID,
            )
            val current = PackageIdentity.classify(info, PorterServer.MANAGER_USER_ID).observed
            if (!baseline.matches(current)) {
                throw SecurityException("The manager is not the installation the server started with")
            }
            return info!!.applicationInfo!!.sourceDir
        }
    }
}
