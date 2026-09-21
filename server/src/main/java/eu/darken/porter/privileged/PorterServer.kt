package eu.darken.porter.privileged

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.Context
import android.content.IContentProvider
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.ddm.DdmHandleAppName
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.RemoteException
import android.os.ServiceManager
import eu.darken.porter.common.AppTransactions
import eu.darken.porter.common.DiscoveredApplication
import eu.darken.porter.common.util.OsUtils
import eu.darken.porter.core.CallerIdentity
import eu.darken.porter.core.ManagerOperations
import eu.darken.porter.core.PorterCore
import eu.darken.porter.core.ServerPolicy
import eu.darken.porter.endpoint.PorterManagerEndpoint
import eu.darken.porter.porsh.PorshConfig
import eu.darken.porter.privileged.ServerConstants.PERMISSION
import eu.darken.porter.privileged.util.Android17Compat
import eu.darken.porter.privileged.util.InstalledPackagesCompat
import eu.darken.porter.privileged.util.PackageIdentity
import eu.darken.porter.protocol.PorterProtocol
import eu.darken.porter.starter.util.IContentProviderCompat
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.system.exitProcess
import moe.shizuku.api.BinderContainer
import rikka.hidden.compat.ActivityManagerApis
import rikka.hidden.compat.DeviceIdleControllerApis
import rikka.hidden.compat.PackageManagerApis
import rikka.hidden.compat.UserManagerApis
import rikka.parcelablelist.ParcelableListSlice
import rikka.shizuku.ShizukuApiConstants
import rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_PERMISSION_GRANTED
import rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SERVER_PATCH_VERSION
import rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SERVER_SECONTEXT
import rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SERVER_UID
import rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SERVER_VERSION
import rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE
import rikka.shizuku.server.ClientRecord
import rikka.shizuku.server.ConfigManager
import rikka.shizuku.server.UserServiceManager
import rikka.shizuku.server.util.HandlerUtil
import rikka.shizuku.server.util.Logger
import rikka.shizuku.server.util.UserHandleCompat

class PorterServer internal constructor(
    val userServiceManager: ShizukuUserServiceManager,
    val clientManager: ShizukuClientManager,
    val configManager: ShizukuConfigManager,
    private val managerAppId: Int,
    private val connectionHistory: ConnectionHistory,
    private val historyWriter: Executor,
    endpointFactory: (PorterServer) -> ShizukuServiceEndpoint,
    porterEndpointFactory: (PorterServer) -> PorterServiceEndpoint,
    managerEndpointFactory: (PorterServer) -> PorterManagerEndpoint = { PorterManagerEndpoint(it.core, it) },
) : ServerPolicy, ManagerOperations {

    val core: PorterCore<ShizukuUserServiceManager, ShizukuClientManager, ShizukuConfigManager> =
        PorterCore(userServiceManager, clientManager, configManager, this)
    val endpoint: ShizukuServiceEndpoint = endpointFactory(this)
    val porterEndpoint: PorterServiceEndpoint = porterEndpointFactory(this)

    /** Handed out over [AppTransactions.GET_MANAGER], to the manager only. */
    val managerEndpoint: PorterManagerEndpoint = managerEndpointFactory(this)
    private val mainHandler = Handler(Looper.myLooper()!!)
    internal lateinit var reconciler: ApkReconciler
    internal val debugLogLeases = DebugLogLeases()

    /**
     * The manager is the installation in Android user 0, the one whose provider the server and
     * the starters deliver to and whose activity every permission prompt is started in. The same
     * package in another user is an ordinary app.
     */
    internal fun isManager(caller: CallerIdentity): Boolean =
        caller.appId() == managerAppId && caller.userId() == MANAGER_USER_ID

    private fun isManager(record: ClientRecord): Boolean = isManager(CallerIdentity(record.uid, record.pid))

    override fun checkCallerManagerPermission(func: String, caller: CallerIdentity): Boolean = isManager(caller)

    override fun checkCallerPermission(func: String, caller: CallerIdentity, record: ClientRecord?): Boolean {
        if (isManager(caller)) {
            return true
        }
        if (configManager.isAccessPaused) throw SecurityException("App access is paused")
        return false
    }

    override fun exit() {
        LOGGER.i("exit")
        exitProcess(0)
    }

    override fun attachUserService(binder: IBinder, token: String) {
        if (configManager.isAccessPaused) throw SecurityException("App access is paused")
        // Above the monitor: reading the descriptor is a synchronous round trip to the service being
        // attached, and Binder has no client-side timeout.
        val interfaceDescriptor = UserServiceManager.getInterfaceDescriptor(binder)
        synchronized(clientManager) {
            core.attachUserService(binder, token, interfaceDescriptor)
        }
    }

    override fun onAttaching(caller: CallerIdentity, packageName: String) {
        if (!isManager(caller)) {
            // Declaring a client permission only decides who gets the binder pushed (see providerSuffix).
            // Terminal clients (porsh) fetch it themselves and declare nothing; they are admitted here on
            // the uid/package check above and gated by the user's explicit decision like any client.
            reconcileRuntimePermission(caller.uid)
        }
    }

    override fun onAttached(record: ClientRecord, created: Boolean, reply: Bundle) {
        LOGGER.d("attachApplication: %s %d %d", record.packageName, record.uid, record.pid)

        if (!isManager(record)) {
            return
        }
        // Both wires reach this hook; the manager is not a permission-gated client on either.
        reply.remove(BIND_APPLICATION_PERMISSION_GRANTED)
        reply.remove(BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE)
        reply.remove(PorterProtocol.REPLY_PERMISSION_GRANTED)
        reply.remove(PorterProtocol.REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE)
        try {
            Android17Compat.grantRuntimePermission(
                MANAGER_APPLICATION_ID,
                WRITE_SECURE_SETTINGS, UserHandleCompat.getUserId(record.uid),
            )
        } catch (e: RemoteException) {
            LOGGER.w(e, "grant WRITE_SECURE_SETTINGS")
        }
    }

    override fun onBound(record: ClientRecord, created: Boolean) {
        if (isManager(record) || !created) {
            return
        }
        val callingUid = record.uid
        val requestPackageName = record.packageName
        val connectedAt = System.currentTimeMillis()
        historyWriter.execute {
            try {
                val info = Android17Compat.getPackageInfo(requestPackageName, 0L, callingUid / 100000)
                val applicationInfo = info?.applicationInfo
                if (applicationInfo != null && applicationInfo.uid == callingUid) {
                    connectionHistory.connected(info, connectedAt)
                }
            } catch (e: RuntimeException) {
                LOGGER.w(e, "Cannot record client connection")
            }
        }
    }

    override fun showPermissionConfirmation(requestCode: Int, record: ClientRecord, caller: CallerIdentity, userId: Int) {
        if (configManager.isAccessPaused) {
            record.dispatchRequestPermissionResult(requestCode, false)
            return
        }
        val legacyOnly = uidUsesLegacyOnly(caller.uid)
        if (legacyOnly == null || (legacyOnly && !Compatibility.isAvailable())) {
            record.dispatchRequestPermissionResult(requestCode, false)
            return
        }
        val ai = Android17Compat.getApplicationInfo(record.packageName, 0L, userId) ?: return

        // The prompt is the manager's, and the manager lives in user 0 whichever user asks; the
        // requester's ApplicationInfo travels with the intent, so the prompt can still name it.
        if (Android17Compat.getPackageInfo(MANAGER_APPLICATION_ID, 0L, MANAGER_USER_ID) == null) {
            LOGGER.w("Manager not found in user %d. Revoke permission", MANAGER_USER_ID)
            record.dispatchRequestPermissionResult(requestCode, false)
            return
        }

        val intent = Intent(ServerConstants.REQUEST_PERMISSION_ACTION)
            .setPackage(MANAGER_APPLICATION_ID)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
            .putExtra("uid", caller.uid)
            .putExtra("pid", caller.pid)
            .putExtra("requestCode", requestCode)
            .putExtra("applicationInfo", ai)
        ActivityManagerApis.startActivityNoThrow(intent, null, MANAGER_USER_ID)
    }

    override fun dispatchPermissionConfirmationResult(requestUid: Int, requestPid: Int, requestCode: Int, allowed: Boolean, onetime: Boolean) {
        synchronized(clientManager) {
            var allowed = allowed
            var onetime = onetime
            val legacyOnly = uidUsesLegacyOnly(requestUid)
            if (allowed && legacyOnly == null) {
                allowed = false
                onetime = true
            }
            val pending = allowed && legacyOnly == true && !Compatibility.isAvailable()
            if (pending) allowed = false

            LOGGER.i(
                "dispatchPermissionConfirmationResult: uid=%d, pid=%d, requestCode=%d, allowed=%s, onetime=%s",
                requestUid, requestPid, requestCode, allowed.toString(), onetime.toString(),
            )

            val records = clientManager.findClients(requestUid)
            if (records.isEmpty()) {
                LOGGER.w("dispatchPermissionConfirmationResult: no client for uid %d was found", requestUid)
            } else {
                for (record in records) {
                    record.allowed = allowed && !configManager.isAccessPaused
                    if (record.pid == requestPid) {
                        record.dispatchRequestPermissionResult(requestCode, record.allowed)
                    }
                }
            }

            if (!onetime) {
                configManager.update(
                    requestUid, PackageManagerApis.getPackagesForUidNoThrow(requestUid),
                    ConfigManager.MASK_PERMISSION or ShizukuConfig.FLAG_PENDING_COMPANION,
                    if (pending) ShizukuConfig.FLAG_PENDING_COMPANION else if (allowed) ConfigManager.FLAG_ALLOWED else ConfigManager.FLAG_DENIED,
                )
            }

            if (!onetime && !pending) {
                setRuntimePermissionsForUid(requestUid, allowed)
            }
        }
    }

    private fun setRuntimePermissionsForUid(uid: Int, allowed: Boolean) {
        val userId = UserHandleCompat.getUserId(uid)
        val legacy = Compatibility.isAvailable()
        for (packageName in PackageManagerApis.getPackagesForUidNoThrow(uid)) {
            val pi = Android17Compat.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS.toLong(), userId) ?: continue
            for (permission in arrayOf(PERMISSION, ServerConstants.LEGACY_PERMISSION)) {
                if (ServerConstants.LEGACY_PERMISSION == permission && !legacy) continue
                if (!ClientRouting.requests(pi.requestedPermissions, permission)) continue
                try {
                    if (allowed) {
                        Android17Compat.grantRuntimePermission(packageName, permission, userId)
                    } else {
                        Android17Compat.revokeRuntimePermission(packageName, permission, userId)
                    }
                } catch (e: Throwable) {
                    LOGGER.w(e, "Unable to synchronize %s for %s", permission, packageName)
                }
            }
        }
    }

    private fun uidUsesLegacyOnly(uid: Int): Boolean? {
        var legacy = false
        var unresolved = false
        val packages = PackageManagerApis.getPackagesForUidNoThrow(uid)
        if (packages.isEmpty()) return null
        for (name in packages) {
            val pi = Android17Compat.getPackageInfo(name, PackageManager.GET_PERMISSIONS.toLong(), UserHandleCompat.getUserId(uid))
            if (pi == null) {
                unresolved = true
                continue
            }
            if (ClientRouting.requests(pi.requestedPermissions, PERMISSION)) return false
            legacy = legacy or ClientRouting.requests(pi.requestedPermissions, ServerConstants.LEGACY_PERMISSION)
        }
        return if (unresolved) null else legacy
    }

    private fun suspendUid(uid: Int) {
        for (record in clientManager.findClients(uid)) record.allowed = false
        for (name in PackageManagerApis.getPackagesForUidNoThrow(uid)) onPermissionRevoked(name)
    }

    internal fun reconcileRuntimePermission(uid: Int) {
        synchronized(clientManager) {
            val entry = configManager.find(uid)
            if (entry == null || (!entry.isAllowed() && !entry.isPendingCompanion())) return
            val userId = UserHandleCompat.getUserId(uid)
            val legacyOnly = uidUsesLegacyOnly(uid) ?: return
            val legacy = Compatibility.isAvailable()
            val mask = ConfigManager.MASK_PERMISSION or ShizukuConfig.FLAG_PENDING_COMPANION
            if (legacyOnly && !legacy) {
                configManager.update(uid, PackageManagerApis.getPackagesForUidNoThrow(uid), mask, ShizukuConfig.FLAG_PENDING_COMPANION)
                suspendUid(uid)
                return
            }
            val pending = entry.isPendingCompanion()
            if (pending) {
                if (!legacyOnly) {
                    configManager.update(uid, null, mask, 0)
                    suspendUid(uid)
                    return
                }
                setRuntimePermissionsForUid(uid, true)
            }
            for (name in PackageManagerApis.getPackagesForUidNoThrow(uid)) {
                val pi = Android17Compat.getPackageInfo(name, PackageManager.GET_PERMISSIONS.toLong(), userId)
                if (pi == null) {
                    if (pending) {
                        suspendUid(uid)
                        return
                    }
                    continue
                }
                val permission = if (ClientRouting.requests(pi.requestedPermissions, PERMISSION)) {
                    PERMISSION
                } else if (legacyOnly && ClientRouting.requests(pi.requestedPermissions, ServerConstants.LEGACY_PERMISSION)) {
                    ServerConstants.LEGACY_PERMISSION
                } else {
                    continue
                }
                if (Android17Compat.checkPermission(permission, name, userId) == PackageManager.PERMISSION_GRANTED) continue
                // A pending grant may still be blocked by Android policy. Active grants respect revocation.
                if (!pending) configManager.update(uid, null, mask, 0)
                suspendUid(uid)
                return
            }
            if (pending) {
                configManager.update(uid, null, mask, ConfigManager.FLAG_ALLOWED)
                for (record in clientManager.findClients(uid)) record.allowed = !configManager.isAccessPaused
            }
        }
    }

    internal fun setGlobalAccess(enabled: Boolean) {
        synchronized(clientManager) {
            val paused = !enabled
            if (configManager.isAccessPaused == paused) return
            configManager.setAccessPaused(paused)
            userServiceManager.setAccessPaused(paused)
            if (!paused) {
                for (uid in configManager.allowedUids()) reconcileRuntimePermission(uid)
            }
            for (record in clientManager.attachedClients()) {
                if (isManager(record)) continue
                val entry = configManager.find(record.uid)
                record.allowed = !paused && entry != null && entry.isAllowed()
                val reply = Bundle()
                reply.putInt(BIND_APPLICATION_SERVER_UID, OsUtils.uid)
                reply.putInt(BIND_APPLICATION_SERVER_VERSION, if (record.apiVersion == -1) 12 else ShizukuApiConstants.SERVER_VERSION)
                reply.putInt(BIND_APPLICATION_SERVER_PATCH_VERSION, ShizukuApiConstants.SERVER_PATCH_VERSION)
                reply.putString(BIND_APPLICATION_SERVER_SECONTEXT, OsUtils.seLinuxContext)
                reply.putBoolean(BIND_APPLICATION_PERMISSION_GRANTED, record.allowed)
                reply.putBoolean(BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, entry != null && entry.isDenied())
                try {
                    val client = record.client
                    if (client != null) {
                        client.bindApplication(reply)
                    } else {
                        record.callback.onPermissionStateChanged(record.allowed, entry != null && entry.isDenied())
                    }
                } catch (e: Throwable) {
                    LOGGER.w(e, "Cannot notify client of global access change")
                }
            }
        }
    }

    private fun getFlagsForUidInternal(uid: Int, mask: Int): Int {
        val entry = configManager.find(uid)
        return if (entry == null) 0 else entry.flags and mask
    }

    override fun getFlagsForUid(uid: Int, mask: Int): Int = getFlagsForUidInternal(uid, mask)

    override fun updateFlagsForUid(uid: Int, mask: Int, value: Int) {
        synchronized(clientManager) {
            var mask = mask
            var value = value
            val userId = UserHandleCompat.getUserId(uid)

            if ((mask and ConfigManager.MASK_PERMISSION) != 0) {
                mask = mask or ShizukuConfig.FLAG_PENDING_COMPANION
                value = value and ShizukuConfig.FLAG_PENDING_COMPANION.inv()
                val legacyOnly = uidUsesLegacyOnly(uid)
                if ((value and ConfigManager.FLAG_ALLOWED) != 0 && legacyOnly == null) {
                    throw IllegalStateException("Cannot read application permissions. Try again.")
                }
                if ((value and ConfigManager.FLAG_ALLOWED) != 0 && legacyOnly == true && !Compatibility.isAvailable()) {
                    value = (value and ConfigManager.MASK_PERMISSION.inv()) or ShizukuConfig.FLAG_PENDING_COMPANION
                    configManager.update(uid, PackageManagerApis.getPackagesForUidNoThrow(uid), mask, value)
                    suspendUid(uid)
                    return
                }
                val allowed = (value and ConfigManager.FLAG_ALLOWED) != 0
                val denied = (value and ConfigManager.FLAG_DENIED) != 0

                val records = clientManager.findClients(uid)
                for (record in records) {
                    if (allowed) {
                        record.allowed = !configManager.isAccessPaused
                    } else {
                        record.allowed = false
                        ActivityManagerApis.forceStopPackageNoThrow(record.packageName, UserHandleCompat.getUserId(record.uid))
                    }
                }
                if (!allowed) {
                    // Daemon user services outlive the client process, so tear down by package, not by attached record.
                    for (packageName in PackageManagerApis.getPackagesForUidNoThrow(uid)) {
                        onPermissionRevoked(packageName)
                    }
                }

                setRuntimePermissionsForUid(uid, allowed)
            }

            configManager.update(uid, PackageManagerApis.getPackagesForUidNoThrow(uid), mask, value)
        }
    }

    private fun onPermissionRevoked(packageName: String) {
        userServiceManager.removeUserServicesForPackage(packageName)
    }

    internal fun getApplications(userId: Int): ParcelableListSlice<PackageInfo> {
        val list = ArrayList<PackageInfo>()
        val users = ArrayList<Int>()
        if (userId == -1) {
            users.addAll(UserManagerApis.getUserIdsNoThrow())
        } else {
            users.add(userId)
        }

        for (user in users) {
            for (pi in InstalledPackagesCompat.getInstalledPackagesNoThrow((PackageManager.GET_META_DATA or PackageManager.GET_PERMISSIONS).toLong(), user)) {
                if (MANAGER_APPLICATION_ID == pi.packageName || ServerConstants.COMPAT_APPLICATION_ID == pi.packageName) continue
                val applicationInfo = pi.applicationInfo ?: continue

                val uid = applicationInfo.uid
                var flags = 0
                val entry = configManager.find(uid)
                if (entry != null) {
                    val entryPackages = entry.packages
                    if (entryPackages != null && !entryPackages.contains(pi.packageName)) continue
                    flags = entry.flags and ConfigManager.MASK_PERMISSION
                }

                // Anything the user decided on is manageable here, declared permission or not (porsh
                // terminals). Undecided packages are only suggested when Porter would push to them.
                if (flags != 0) {
                    list.add(pi)
                } else if (route(pi) != null) {
                    list.add(pi)
                }
            }
        }
        return ParcelableListSlice(list)
    }

    internal fun writeDiscovery(userId: Int, reply: Parcel) {
        val users: Collection<Int> = if (userId == -1) UserManagerApis.getUserIdsNoThrow() else listOf(userId)
        if (users.isEmpty()) throw IllegalStateException("Cannot enumerate Android users")
        val apps = ArrayList<DiscoveredApplication>()
        val failedUsers = ArrayList<Int>()
        val companion = Compatibility.isAvailable()
        for (uid in configManager.allowedUids()) reconcileRuntimePermission(uid)
        for (user in users) {
            try {
                val snapshotStartedAt = System.currentTimeMillis()
                val installed = InstalledPackagesCompat.getInstalledPackages(
                    (PackageManager.GET_META_DATA or PackageManager.GET_PERMISSIONS).toLong(), user,
                )
                if (installed == null || installed.isEmpty()) throw IllegalStateException("Empty package enumeration")
                connectionHistory.pruneUser(user, installed, snapshotStartedAt)
                for (info in installed) {
                    val applicationInfo = info.applicationInfo
                    if (applicationInfo == null || MANAGER_APPLICATION_ID == info.packageName ||
                        ServerConstants.COMPAT_APPLICATION_ID == info.packageName
                    ) {
                        continue
                    }
                    val decision = configManager.find(applicationInfo.uid)
                    val lastConnected = connectionHistory.get(info)
                    val declared = ClientRouting.requests(info.requestedPermissions, PERMISSION) ||
                        ClientRouting.requests(info.requestedPermissions, ServerConstants.LEGACY_PERMISSION)
                    val decisionPackages = decision?.packages
                    val managed = decision != null && (decisionPackages == null || decisionPackages.contains(info.packageName))
                    if (!declared && !managed && lastConnected == 0L) continue
                    val authorization = if (decision != null) decision.flags and (ConfigManager.MASK_PERMISSION or ShizukuConfig.FLAG_PENDING_COMPANION) else 0
                    try {
                        val app = ApplicationDiscovery.describe(info, authorization, companion, lastConnected)
                        if (app != null) apps.add(app)
                    } catch (e: RuntimeException) {
                        if (!failedUsers.contains(user)) failedUsers.add(user)
                        LOGGER.w(e, "Cannot describe application " + info.packageName)
                    }
                }
            } catch (e: Exception) {
                if (!failedUsers.contains(user)) failedUsers.add(user)
                LOGGER.w(e, "Cannot discover applications for user $user")
            }
        }
        reply.writeNoException()
        reply.writeInt(DiscoveredApplication.WIRE_VERSION)
        reply.writeIntArray(failedUsers.toIntArray())
        ParcelableListSlice(apps).writeToParcel(reply, 0)
    }

    @Throws(Exception::class)
    internal fun compatibilitySetup(operation: Int, snapshot: String?): Bundle {
        synchronized(clientManager) {
            return CompatibilitySetupHandler(
                configManager,
                { uid, value -> updateFlagsForUid(uid, ConfigManager.MASK_PERMISSION, value) },
                {
                    for (uid in configManager.allowedUids()) reconcileRuntimePermission(uid)
                    mainHandler.post {
                        BinderSender.resetDelivery()
                        sendBinderToClient()
                    }
                },
            ).execute(operation, snapshot)
        }
    }

    internal fun sendBinderToClient() {
        for (userId in UserManagerApis.getUserIdsNoThrow()) {
            sendBinderToClient(endpoint, porterEndpoint, userId)
        }
    }

    internal fun sendBinderToManager() {
        sendBinderToManager(porterEndpoint, MANAGER_USER_ID)
    }

    companion object {

        const val MANAGER_APPLICATION_ID: String = PorterProtocol.MANAGER_APPLICATION_ID

        /** The Android user the manager is the manager in. */
        const val MANAGER_USER_ID: Int = 0

        private val LOGGER = Logger("Service")

        @JvmStatic
        fun main(args: Array<String>) {
            DdmHandleAppName.setAppName("porter_server", 0)
            PorshConfig.setLibraryPath(System.getProperty("porter.library.path"))
            PorshConfig.init(ShizukuApiConstants.BINDER_DESCRIPTOR, 30000)

            Looper.prepareMainLooper()
            bootstrap(::ShizukuServiceEndpoint, ::PorterServiceEndpoint)
            Looper.loop()
        }

        private fun waitSystemService(name: String) {
            while (ServiceManager.getService(name) == null) {
                try {
                    LOGGER.i("service $name is not started, wait 1s.")
                    Thread.sleep(1000)
                } catch (e: InterruptedException) {
                    LOGGER.w(e.message ?: "", e)
                }
            }
        }

        fun getManagerApplicationInfo(): ApplicationInfo? =
            Android17Compat.getApplicationInfo(MANAGER_APPLICATION_ID, 0L, MANAGER_USER_ID)

        /**
         * The startup verdict on the manager lookup; `0` means carry on. A failed lookup exits for
         * the same reason an absent manager does: the alternative is publishing access while holding no
         * verified baseline, and the manager restarts the server anyway.
         */
        internal fun managerStartupExitCode(result: PackageIdentity.Result): Int =
            if (result.state == PackageIdentity.State.PRESENT) 0 else ServerConstants.MANAGER_APP_NOT_FOUND

        fun bootstrap(
            endpointFactory: (PorterServer) -> ShizukuServiceEndpoint,
            porterEndpointFactory: (PorterServer) -> PorterServiceEndpoint,
            managerEndpointFactory: (PorterServer) -> PorterManagerEndpoint = { PorterManagerEndpoint(it.core, it) },
        ): PorterServer {
            LOGGER.i("starting server...")

            waitSystemService("package")
            waitSystemService(Context.ACTIVITY_SERVICE)
            waitSystemService(Context.USER_SERVICE)
            waitSystemService(Context.APP_OPS_SERVICE)

            // One signed observation, so the app id that authorises manager calls and the identity the
            // reconciler compares against can never describe two different installations. A failed
            // lookup exits too: publishing access while holding no verified baseline is worse than a
            // restart, and the manager restarts the server anyway.
            val manager = PackageIdentity.of(MANAGER_APPLICATION_ID, MANAGER_USER_ID)
            if (managerStartupExitCode(manager) != 0) {
                LOGGER.w("manager app is %s in user 0, exiting...", manager.state)
                exitProcess(managerStartupExitCode(manager))
            }

            val observed = manager.observed ?: throw AssertionError("manager observed is null")

            val configManager = ShizukuConfigManager()
            val clientManager = ShizukuClientManager(configManager)
            val userServiceManager = ShizukuUserServiceManager()

            val service = PorterServer(
                userServiceManager,
                clientManager,
                configManager,
                observed.appId,
                ConnectionHistory(File("/data/user_de/0/com.android.shell/porter-connections.json")),
                Executors.newSingleThreadExecutor(),
                endpointFactory,
                porterEndpointFactory,
                managerEndpointFactory,
            )

            HandlerUtil.mainHandler = service.mainHandler

            // A debug build logs its debug detail unconditionally, the way it always has. The gate
            // exists to keep that detail out of release builds except while a recording wants it.
            Logger.setDebugAlways(BuildConfig.DEBUG)

            userServiceManager.setAccessPaused(configManager.isAccessPaused)

            val reconciler = ApkReconciler(
                MANAGER_APPLICATION_ID,
                PackageIdentity.Identity(MANAGER_APPLICATION_ID, observed.appId, observed.signerDigests),
                userServiceManager,
                ApkReconciler.SystemPackageOracle(),
                ApkReconciler.ExecutorScheduler(),
                ApkReconciler.ExitHandler { code -> exitProcess(code) },
            )
            service.reconciler = reconciler
            userServiceManager.setReconciler(reconciler)

            BinderSender.register(service.endpoint, service.porterEndpoint)
            try {
                PermissionObserver.register { uid -> service.mainHandler.post { service.reconcileRuntimePermission(uid) } }
            } catch (e: ReflectiveOperationException) {
                LOGGER.w(e, "Permission observer unavailable; reconciling at startup and client attach")
            } catch (e: RuntimeException) {
                LOGGER.w(e, "Permission observer unavailable; reconciling at startup and client attach")
            }

            service.mainHandler.post {
                for (uid in configManager.allowedUids()) {
                    service.reconcileRuntimePermission(uid)
                }
                service.sendBinderToManager()
                service.sendBinderToClient()
            }

            reconciler.start()

            return service
        }

        internal fun route(client: PackageInfo?): ClientRouting.Wire? {
            val requestedPermissions = client?.requestedPermissions ?: return null
            val permissions = client.permissions
            if (permissions != null) {
                for (permission in permissions) {
                    val name = permission.name
                    if (name != null && name.endsWith(".permission.MANAGER")) return null
                }
            }
            if (!ClientRouting.requests(requestedPermissions, PERMISSION) &&
                !ClientRouting.requests(requestedPermissions, ServerConstants.LEGACY_PERMISSION)
            ) {
                return null
            }
            return ClientRouting.route(requestedPermissions, Compatibility.isAvailable())
        }

        private fun sendBinderToClient(shizukuBinder: Binder, porterBinder: Binder, userId: Int) {
            try {
                val packages = InstalledPackagesCompat.getInstalledPackagesNoThrow(
                    PackageManager.GET_PERMISSIONS.toLong(), userId,
                )
                    .stream()
                    .filter { pi -> pi != null && pi.requestedPermissions != null }
                    .filter { pi -> route(pi) != null }

                LOGGER.i("sending binders")
                packages
                    .parallel()
                    .forEach { pi ->
                        val wire = route(pi)
                        sendBinderToUserApp(
                            wire, if (wire == ClientRouting.Wire.PORTER) porterBinder else shizukuBinder,
                            pi.packageName, userId,
                        )
                    }
                LOGGER.i("sent binders")
            } catch (tr: Throwable) {
                LOGGER.e("exception when call getInstalledPackages", tr)
            }
        }

        internal fun sendBinderToManager(binder: Binder, userId: Int) {
            var success = sendBinderToUserApp(ClientRouting.Wire.PORTER, binder, MANAGER_APPLICATION_ID, userId)
            if (!success) {
                // For unknown reason, sometimes this could happens
                // Kill Shizuku app and try again could work
                try {
                    LOGGER.e("kill %s in user %d and try again", MANAGER_APPLICATION_ID, userId)
                    ActivityManagerApis.forceStopPackageNoThrow(MANAGER_APPLICATION_ID, userId)
                    try {
                        Thread.sleep(1000)
                    } catch (ignored: InterruptedException) {
                    }
                    success = sendBinderToUserApp(ClientRouting.Wire.PORTER, binder, MANAGER_APPLICATION_ID, userId)
                    if (success) {
                        LOGGER.e("retry succeeded")
                    } else {
                        LOGGER.e("retry failed")
                    }
                } catch (tr: Throwable) {
                    LOGGER.e(tr, "retry failed")
                }
            }
        }

        internal fun sendBinderToUserApp(wire: ClientRouting.Wire?, binder: Binder, packageName: String, userId: Int): Boolean {
            try {
                DeviceIdleControllerApis.addPowerSaveTempWhitelistApp(
                    packageName, 30 * 1000L, userId,
                    316 /* PowerExemptionManager#REASON_SHELL */, "shell",
                )
            } catch (tr: Throwable) {
                LOGGER.e(tr, "Failed to add %d:%s to power save temp whitelist", userId, packageName)
            }

            if (wire == null) return false
            val name = packageName + ClientRouting.providerSuffix(wire)
            var provider: IContentProvider? = null

            /*
             When we pass IBinder through binder (and really crossed process), the receive side (here is system_server process)
             will always get a new instance of android.os.BinderProxy.

             In the implementation of getContentProviderExternal and removeContentProviderExternal, received
             IBinder is used as the key of a HashMap. But hashCode() is not implemented by BinderProxy, so
             removeContentProviderExternal will never work.

             Luckily, we can pass null. When token is token, count will be used.
             */
            val token: IBinder? = null

            try {
                provider = ActivityManagerApis.getContentProviderExternal(name, userId, token, name)
                if (provider == null) {
                    LOGGER.e("provider is null %s %d", name, userId)
                    return false
                }
                if (!provider.asBinder().pingBinder()) {
                    LOGGER.e("provider is dead %s %d", name, userId)
                    return false
                }

                val extra = Bundle()
                val method: String
                if (wire == ClientRouting.Wire.PORTER) {
                    extra.putBinder(PorterProtocol.DELIVERY_EXTRA_BINDER, binder)
                    method = PorterProtocol.DELIVERY_METHOD_SEND_BINDER
                } else {
                    extra.putParcelable("moe.shizuku.privileged.api.intent.extra.BINDER", BinderContainer(binder))
                    method = "sendBinder"
                }

                val reply = IContentProviderCompat.call(provider, null, null, name, method, null, extra)
                if (reply != null) {
                    LOGGER.i("send binder to user app %s in user %d", packageName, userId)
                    return true
                } else {
                    LOGGER.w("failed to send binder to user app %s in user %d", packageName, userId)
                    return false
                }
            } catch (tr: Throwable) {
                LOGGER.e(tr, "failed to send binder to user app %s in user %d", packageName, userId)
                return false
            } finally {
                if (provider != null) {
                    try {
                        ActivityManagerApis.removeContentProviderExternal(name, token)
                    } catch (tr: Throwable) {
                        LOGGER.w(tr, "removeContentProviderExternal")
                    }
                }
            }
        }
    }
}
