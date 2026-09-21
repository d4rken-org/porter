package eu.darken.porter.privileged

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Binder
import android.os.Bundle
import android.os.Parcel
import android.os.Process
import android.os.RemoteException
import com.google.gson.Gson
import eu.darken.porter.common.CompatibilitySetup
import eu.darken.porter.common.DiscoveredApplication
import eu.darken.porter.common.GlobalAccess
import eu.darken.porter.common.UserServiceLaunch
import eu.darken.porter.common.util.OsUtils
import eu.darken.porter.core.CallerIdentity
import eu.darken.porter.core.ClientCallback
import eu.darken.porter.endpoint.PorterClientCallback
import eu.darken.porter.protocol.PorterProtocol
import eu.darken.porter.server.IPorterApplication
import moe.shizuku.server.IShizukuApplication
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.MockedStatic
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import org.robolectric.util.ReflectionHelpers
import rikka.hidden.compat.ActivityManagerApis
import rikka.hidden.compat.PackageManagerApis
import rikka.hidden.compat.PermissionManagerApis
import rikka.hidden.compat.UserManagerApis
import rikka.parcelablelist.ParcelableListSlice
import rikka.shizuku.ShizukuApiConstants
import rikka.shizuku.server.ClientRecord
import rikka.shizuku.server.ConfigManager
import rikka.shizuku.server.legacy.LegacyClientCallback
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ServiceAuthorizationTest {

    private lateinit var service: PorterServer
    private lateinit var config: ShizukuConfigManager
    private lateinit var clients: ShizukuClientManager
    private lateinit var userServices: ShizukuUserServiceManager
    private lateinit var history: ConnectionHistory
    private lateinit var client: ClientRecord
    private lateinit var installed: PackageInfo
    /** What a user-0 package lookup answers, by name; absent means no such package. */
    private val packageInfos = HashMap<String, PackageInfo?>()
    private var packages: MockedStatic<PackageManagerApis>? = null
    private var permissions: MockedStatic<PermissionManagerApis>? = null
    private var activities: MockedStatic<ActivityManagerApis>? = null

    @Before
    fun setup() {
        config = mock(ShizukuConfigManager::class.java)
        clients = mock(ShizukuClientManager::class.java)
        userServices = mock(ShizukuUserServiceManager::class.java)
        history = mock(ConnectionHistory::class.java)
        service = PorterServer(
            userServices, clients, config, MANAGER_UID, history,
            Executor { it.run() }, ::ShizukuServiceEndpoint, ::PorterServiceEndpoint,
        )
        client = ClientRecord(CLIENT_UID, CLIENT_PID, mock(IShizukuApplication::class.java), "test.client", 13)
        client.allowed = true
        `when`(clients.findClients(CLIENT_UID)).thenReturn(listOf(client))
        `when`(clients.findClient(CLIENT_UID, CLIENT_PID)).thenReturn(client)
        `when`(config.find(CLIENT_UID)).thenReturn(ShizukuConfig.PackageEntry(CLIENT_UID, ConfigManager.FLAG_ALLOWED))
        installed = PackageInfo()
        installed.packageName = client.packageName
        installed.requestedPermissions = arrayOf(ServerConstants.PERMISSION)
        packageInfos[client.packageName] = installed
        // Android17Compat and Compatibility are Kotlin objects, which a static mock cannot intercept,
        // so the stubs sit one level below them on the hidden-API calls the compat layer makes.
        packages = mockStatic(PackageManagerApis::class.java).also {
            it.`when`<List<String>> { PackageManagerApis.getPackagesForUidNoThrow(CLIENT_UID) }.thenReturn(listOf(client.packageName))
            it.`when`<PackageInfo?> { PackageManagerApis.getPackageInfo(anyString(), anyLong(), anyInt()) }.thenAnswer { invocation ->
                if (invocation.getArgument<Int>(2) == 0) packageInfos[invocation.getArgument(0)] else null
            }
        }
        permissions = mockStatic(PermissionManagerApis::class.java)
        activities = mockStatic(ActivityManagerApis::class.java)
        ShadowBinder.setCallingUid(CLIENT_UID)
        ShadowBinder.setCallingPid(CLIENT_PID)
    }

    /** Closes whatever setup managed to open, so a failed setup cannot leak a mock into the next test. */
    @After
    fun close() {
        activities?.close()
        permissions?.close()
        packages?.close()
        ShadowBinder.reset()
    }

    private val permissionMocks: MockedStatic<PermissionManagerApis> get() = permissions!!
    private val activityMocks: MockedStatic<ActivityManagerApis> get() = activities!!

    private fun checkPermission(permission: String, result: Int) {
        permissionMocks.`when`<Int> { PermissionManagerApis.checkPermission(permission, client.packageName, 0) }.thenReturn(result)
    }

    /** The package lookup answers nothing for the client, as a lookup that failed or found no package does. */
    private fun clientPackageMissing() {
        packageInfos[client.packageName] = null
    }

    /**
     * Installs a manager and a companion signed alike, the companion declaring the legacy
     * permission at dangerous level, which is what [Compatibility.isAvailable] checks for.
     */
    private fun companionAvailable() {
        val permission = PermissionInfo()
        permission.name = ServerConstants.LEGACY_PERMISSION
        permission.packageName = ServerConstants.COMPAT_APPLICATION_ID
        permission.protectionLevel = PermissionInfo.PROTECTION_DANGEROUS
        val manager = PackageInfo()
        val companion = PackageInfo()
        companion.permissions = arrayOf(permission)
        for (info in listOf(manager, companion)) {
            val signingInfo = mock(SigningInfo::class.java)
            `when`(signingInfo.apkContentsSigners).thenReturn(arrayOf(Signature("aabb")))
            info.signingInfo = signingInfo
        }
        packageInfos[PorterServer.MANAGER_APPLICATION_ID] = manager
        packageInfos[ServerConstants.COMPAT_APPLICATION_ID] = companion
    }

    private fun grantedRuntimePermission(permission: String, mode: org.mockito.verification.VerificationMode = times(1)) {
        permissionMocks.verify({ PermissionManagerApis.grantRuntimePermission(client.packageName, permission, 0) }, mode)
    }

    private fun revokedRuntimePermission(permission: String) {
        permissionMocks.verify { PermissionManagerApis.revokeRuntimePermission(client.packageName, permission, 0) }
    }

    /**
     * Stands [packageManager] in as the application's for [block], which is where
     * `InstalledPackagesCompat` looks first; the field is the one Robolectric itself sets.
     */
    private fun <T> withPackageManager(packageManager: PackageManager, block: () -> T): T {
        val activityThread = Class.forName("android.app.ActivityThread").getMethod("currentActivityThread").invoke(null)
        val real = ReflectionHelpers.getField<Application>(activityThread, "mInitialApplication")
        val application = object : Application() {
            override fun getPackageManager(): PackageManager = packageManager
        }
        ReflectionHelpers.setField(activityThread, "mInitialApplication", application)
        try {
            return block()
        } finally {
            ReflectionHelpers.setField(activityThread, "mInitialApplication", real)
        }
    }

    /**
     * A package manager whose per-user listing is [perUser]; the method is hidden API, so it is
     * answered by name rather than stubbed.
     */
    private fun packageManagerListing(perUser: (Int) -> List<PackageInfo>): PackageManager =
        mock(PackageManager::class.java) { invocation ->
            if (invocation.method.name == "getInstalledPackagesAsUser") perUser(invocation.getArgument(1)) else null
        }

    @Test
    fun grantedClientStillCannotInvokeManagerOperations() {
        assertThrows(SecurityException::class.java) { service.endpoint.getFlagsForUid(CLIENT_UID, ConfigManager.MASK_PERMISSION) }
        assertThrows(SecurityException::class.java) { service.endpoint.updateFlagsForUid(CLIENT_UID, ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_ALLOWED) }
        assertThrows(SecurityException::class.java) { service.endpoint.dispatchPermissionConfirmationResult(CLIENT_UID, CLIENT_PID, 1, Bundle()) }
        assertThrows(SecurityException::class.java) { service.endpoint.attachUserService(null, Bundle()) }
        assertThrows(SecurityException::class.java) { service.endpoint.exit() }
        verifyNoInteractions(config, userServices)
    }

    @Test
    fun diagnosticsAndApplicationsTransactionsRequireManager() {
        for (code in intArrayOf(
            ServerConstants.BINDER_TRANSACTION_getDiagnostics, ServerConstants.BINDER_TRANSACTION_getApplications,
            DiscoveredApplication.TRANSACTION, GlobalAccess.TRANSACTION, CompatibilitySetup.TRANSACTION,
        )) {
            val request = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                request.writeInterfaceToken(ShizukuApiConstants.BINDER_DESCRIPTOR)
                request.setDataPosition(0)
                // The shadow binder writes the refusal into the reply, as the real one does over the wire.
                service.endpoint.transact(code, request, reply, 0)
                reply.setDataPosition(0)
                assertThrows(SecurityException::class.java) { reply.readException() }
            } finally {
                request.recycle()
                reply.recycle()
            }
        }
    }

    @Test
    fun managerUidCanReadFlagsButOtherAppsCannot() {
        ShadowBinder.setCallingUid(MANAGER_UID)
        assertEquals(ConfigManager.FLAG_ALLOWED, service.endpoint.getFlagsForUid(CLIENT_UID, ConfigManager.MASK_PERMISSION))
        ShadowBinder.setCallingUid(CLIENT_UID)
        assertThrows(SecurityException::class.java) { service.endpoint.getFlagsForUid(CLIENT_UID, ConfigManager.MASK_PERMISSION) }
    }

    @Test
    fun permissionRevocationDropsGrantAndTerminatesUserServices() {
        checkPermission(ServerConstants.PERMISSION, PackageManager.PERMISSION_DENIED)
        service.reconcileRuntimePermission(CLIENT_UID)
        assertFalse(client.allowed)
        verify(config).update(CLIENT_UID, null, ConfigManager.MASK_PERMISSION or ShizukuConfig.FLAG_PENDING_COMPANION, 0)
        verify(userServices).removeUserServicesForPackage(client.packageName)
        assertThrows(SecurityException::class.java) { service.core.enforceCallingPermission("client operation", CallerIdentity(CLIENT_UID, CLIENT_PID)) }
    }

    @Test
    fun existingGrantSurvivesWhilePrimaryRuntimePermissionIsGranted() {
        checkPermission(ServerConstants.PERMISSION, PackageManager.PERMISSION_GRANTED)
        service.reconcileRuntimePermission(CLIENT_UID)
        assertTrue(client.allowed)
        verify(config, never()).update(anyInt(), any(), anyInt(), anyInt())
        verifyNoInteractions(userServices)
        service.core.enforceCallingPermission("client operation", CallerIdentity(CLIENT_UID, CLIENT_PID))
    }

    @Test
    fun legacyGrantRequiresCompanionEvenWhenAndroidPermissionRemainsGranted() {
        installed.requestedPermissions = arrayOf(ServerConstants.LEGACY_PERMISSION)
        checkPermission(ServerConstants.LEGACY_PERMISSION, PackageManager.PERMISSION_GRANTED)
        service.reconcileRuntimePermission(CLIENT_UID)
        assertFalse(client.allowed)
        verify(userServices).removeUserServicesForPackage(client.packageName)
    }

    @Test
    fun trustedLegacyGrantSurvives() {
        installed.requestedPermissions = arrayOf(ServerConstants.LEGACY_PERMISSION)
        companionAvailable()
        checkPermission(ServerConstants.LEGACY_PERMISSION, PackageManager.PERMISSION_GRANTED)
        service.reconcileRuntimePermission(CLIENT_UID)
        assertTrue(client.allowed)
        verifyNoInteractions(userServices)
    }

    @Test
    fun legacyGrantCannotOverrideRevokedPorterPermissionForDualClient() {
        installed.requestedPermissions = arrayOf(ServerConstants.PERMISSION, ServerConstants.LEGACY_PERMISSION)
        companionAvailable()
        checkPermission(ServerConstants.PERMISSION, PackageManager.PERMISSION_DENIED)
        checkPermission(ServerConstants.LEGACY_PERMISSION, PackageManager.PERMISSION_GRANTED)
        service.reconcileRuntimePermission(CLIENT_UID)
        assertFalse(client.allowed)
        verify(userServices).removeUserServicesForPackage(client.packageName)
    }

    @Test
    fun terminalClientWithoutRuntimePermissionKeepsExplicitConsent() {
        installed.requestedPermissions = null
        service.reconcileRuntimePermission(CLIENT_UID)
        assertTrue(client.allowed)
        verifyNoInteractions(userServices)
    }

    @Test
    fun grantingPorterAccessDoesNotTouchLegacyPermissionWithoutCompanion() {
        ShadowBinder.setCallingUid(MANAGER_UID)
        installed.requestedPermissions = arrayOf(ServerConstants.PERMISSION, ServerConstants.LEGACY_PERMISSION)
        service.updateFlagsForUid(CLIENT_UID, ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_ALLOWED)
        verify(config).update(CLIENT_UID, listOf(client.packageName), ConfigManager.MASK_PERMISSION or ShizukuConfig.FLAG_PENDING_COMPANION, ConfigManager.FLAG_ALLOWED)
        grantedRuntimePermission(ServerConstants.PERMISSION)
        grantedRuntimePermission(ServerConstants.LEGACY_PERMISSION, never())
    }

    @Test
    fun explicitRevocationStopsAttachedClientAndItsUserServices() {
        ShadowBinder.setCallingUid(MANAGER_UID)
        service.updateFlagsForUid(CLIENT_UID, ConfigManager.MASK_PERMISSION, 0)
        assertFalse(client.allowed)
        activityMocks.verify { ActivityManagerApis.forceStopPackageNoThrow(client.packageName, 0) }
        revokedRuntimePermission(ServerConstants.PERMISSION)
        verify(userServices).removeUserServicesForPackage(client.packageName)
        verify(config).update(CLIENT_UID, listOf(client.packageName), ConfigManager.MASK_PERMISSION or ShizukuConfig.FLAG_PENDING_COMPANION, 0)
    }

    @Test
    fun explicitRevocationAlsoStopsDaemonWithoutAttachedClient() {
        ShadowBinder.setCallingUid(MANAGER_UID)
        `when`(clients.findClients(CLIENT_UID)).thenReturn(listOf())
        service.updateFlagsForUid(CLIENT_UID, ConfigManager.MASK_PERMISSION, 0)
        verify(userServices).removeUserServicesForPackage(client.packageName)
        revokedRuntimePermission(ServerConstants.PERMISSION)
    }

    @Test
    fun managerPackagesNeverReceiveClientBinder() {
        val managerPermission = PermissionInfo()
        managerPermission.name = "foreign.fork.permission.MANAGER"
        installed.permissions = arrayOf(managerPermission)
        assertNull(PorterServer.route(installed))
    }

    @Test
    fun acceptedNewConnectionIsRecordedWithoutGrantingAccess() {
        `when`(clients.findClient(CLIENT_UID, CLIENT_PID)).thenReturn(null)
        `when`(clients.attach(anyK(CallerIdentity::class.java), anyK(ClientCallback::class.java), eqK(client.packageName), anyInt())).thenReturn(client)
        installed.applicationInfo = ApplicationInfo().also { it.uid = CLIENT_UID }
        installed.firstInstallTime = 100
        val args = Bundle()
        args.putString(ShizukuApiConstants.ATTACH_APPLICATION_PACKAGE_NAME, client.packageName)
        val callback = mock(IShizukuApplication::class.java)
        service.endpoint.attachApplication(callback, args)
        verify(callback).bindApplication(any())
        verify(history).connected(eq(installed), anyLong())
        verify(config, never()).update(anyInt(), any(), anyInt(), anyInt())
    }

    @Test
    fun failedConnectionReplyDoesNotCreateHistory() {
        `when`(clients.findClient(CLIENT_UID, CLIENT_PID)).thenReturn(null)
        `when`(clients.attach(anyK(CallerIdentity::class.java), anyK(ClientCallback::class.java), eqK(client.packageName), anyInt())).thenReturn(client)
        val args = Bundle()
        args.putString(ShizukuApiConstants.ATTACH_APPLICATION_PACKAGE_NAME, client.packageName)
        val callback = mock(IShizukuApplication::class.java)
        doThrow(RemoteException()).`when`(callback).bindApplication(any())
        service.endpoint.attachApplication(callback, args)
        verifyNoInteractions(history)
    }

    /**
     * Without the client-manager monitor the endpoint holds across an attach transaction, a pause
     * landing between the record's creation and its reply overtakes that reply: the client is told
     * it is denied and then told it is granted, and believes the later answer.
     */
    @Test
    fun aGlobalPauseCannotOvertakeAnAttachReply() {
        `when`(clients.findClient(CLIENT_UID, CLIENT_PID)).thenReturn(null)
        `when`(clients.attach(anyK(CallerIdentity::class.java), anyK(ClientCallback::class.java), eqK(client.packageName), anyInt())).thenReturn(client)
        var pause: Thread? = null
        val pauseStillBlocked = AtomicBoolean()
        val callback = object : IShizukuApplication.Stub() {
            override fun bindApplication(data: Bundle?) {
                val thread = Thread { service.setGlobalAccess(false) }
                pause = thread
                thread.start()
                try {
                    thread.join(500)
                } catch (ignored: InterruptedException) {
                }
                pauseStillBlocked.set(thread.isAlive)
            }

            override fun dispatchRequestPermissionResult(code: Int, data: Bundle?) {}
            override fun showPermissionConfirmation(uid: Int, pid: Int, name: String?, code: Int) {}
        }
        val args = Bundle()
        args.putString(ShizukuApiConstants.ATTACH_APPLICATION_PACKAGE_NAME, client.packageName)
        val request = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            request.writeInterfaceToken(ShizukuApiConstants.BINDER_DESCRIPTOR)
            request.writeStrongBinder(callback.asBinder())
            request.writeInt(1)
            args.writeToParcel(request, 0)
            request.setDataPosition(0)
            assertTrue(service.endpoint.transact(Binder.FIRST_CALL_TRANSACTION + 17, request, reply, 0))
        } finally {
            request.recycle()
            reply.recycle()
        }
        assertTrue(pauseStillBlocked.get())
        pause!!.join(500)
    }

    @Test
    fun discoveryKeepsOtherAppsAfterOversizedEntryAndPreservesFailedProfileHistory() {
        ShadowBinder.setCallingUid(MANAGER_UID)
        val nativeApp = installedApp("native", CLIENT_UID, ServerConstants.PERMISSION)
        val oversized = installedApp("x".repeat(30000), CLIENT_UID + 1, ServerConstants.PERMISSION)
        val legacy = installedApp("legacy", CLIENT_UID + 2, ServerConstants.LEGACY_PERMISSION)
        val ownerPackages = listOf(nativeApp, oversized, legacy)
        val listing = packageManagerListing { user ->
            if (user == 0) ownerPackages else throw SecurityException("profile unavailable")
        }
        mockStatic(UserManagerApis::class.java).use { users ->
            withPackageManager(listing) {
                users.`when`<List<Int>> { UserManagerApis.getUserIdsNoThrow() }.thenReturn(listOf(0, 10))
                val request = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    request.writeInterfaceToken(ShizukuApiConstants.BINDER_DESCRIPTOR)
                    request.writeInt(-1)
                    request.setDataPosition(0)
                    assertTrue(service.endpoint.transact(DiscoveredApplication.TRANSACTION, request, reply, 0))
                    reply.setDataPosition(0)
                    reply.readException()
                    assertEquals(DiscoveredApplication.WIRE_VERSION, reply.readInt())
                    assertArrayEquals(intArrayOf(0, 10), reply.createIntArray())
                    @Suppress("UNCHECKED_CAST")
                    val apps = (ParcelableListSlice.CREATOR.createFromParcel(reply) as ParcelableListSlice<DiscoveredApplication>).list
                    assertEquals(2, apps.size)
                    assertEquals("native", apps[0].applicationInfo.packageName)
                    assertEquals(DiscoveredApplication.ALLOWED, apps[0].authorization)
                    assertEquals(DiscoveredApplication.NEEDS_COMPANION, apps[1].connectionStatus)
                    verify(history).pruneUser(eq(0), eqK(ownerPackages), anyLong())
                    verify(history, never()).pruneUser(eq(10), anyK(), anyLong())
                } finally {
                    request.recycle()
                    reply.recycle()
                }
            }
        }
    }

    private fun pendingLegacy(): ShizukuConfig.PackageEntry {
        installed.requestedPermissions = arrayOf(ServerConstants.LEGACY_PERMISSION)
        val entry = ShizukuConfig.PackageEntry(CLIENT_UID, ShizukuConfig.FLAG_PENDING_COMPANION)
        entry.packages!!.add(client.packageName)
        `when`(config.find(CLIENT_UID)).thenReturn(entry)
        client.allowed = false
        return entry
    }

    @Test
    fun missingCompanionStoresIntentWithoutGrantingBinderAccess() {
        installed.requestedPermissions = arrayOf(ServerConstants.LEGACY_PERMISSION)
        ShadowBinder.setCallingUid(MANAGER_UID)
        service.updateFlagsForUid(CLIENT_UID, ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_ALLOWED)
        verify(config).update(
            CLIENT_UID, listOf(client.packageName),
            ConfigManager.MASK_PERMISSION or ShizukuConfig.FLAG_PENDING_COMPANION, ShizukuConfig.FLAG_PENDING_COMPANION,
        )
        permissionMocks.verify({ PermissionManagerApis.grantRuntimePermission(anyString(), anyString(), anyInt()) }, never())
        assertFalse(client.allowed)
        ShadowBinder.setCallingUid(CLIENT_UID)
        assertThrows(SecurityException::class.java) { service.core.enforceCallingPermission("client operation", CallerIdentity(CLIENT_UID, CLIENT_PID)) }
    }

    @Test
    fun pendingAccessActivatesOnlyAfterTrustedCompanionAndVerifiedRuntimeGrant() {
        val entry = pendingLegacy()
        assertFalse(entry.isAllowed())
        assertFalse(entry.isDenied())
        service.reconcileRuntimePermission(CLIENT_UID)
        assertFalse(client.allowed)
        companionAvailable()
        checkPermission(ServerConstants.LEGACY_PERMISSION, PackageManager.PERMISSION_GRANTED)
        service.reconcileRuntimePermission(CLIENT_UID)
        grantedRuntimePermission(ServerConstants.LEGACY_PERMISSION)
        verify(config).update(CLIENT_UID, null, ConfigManager.MASK_PERMISSION or ShizukuConfig.FLAG_PENDING_COMPANION, ConfigManager.FLAG_ALLOWED)
        assertTrue(client.allowed)
    }

    @Test
    fun failedRuntimeGrantDoesNotActivatePendingAccess() {
        pendingLegacy()
        companionAvailable()
        checkPermission(ServerConstants.LEGACY_PERMISSION, PackageManager.PERMISSION_DENIED)
        service.reconcileRuntimePermission(CLIENT_UID)
        assertFalse(client.allowed)
        verify(config, never()).update(anyInt(), any(), anyInt(), eq(ConfigManager.FLAG_ALLOWED))
    }

    @Test
    fun removingCompanionSuspendsAccessButKeepsIntent() {
        installed.requestedPermissions = arrayOf(ServerConstants.LEGACY_PERMISSION)
        service.reconcileRuntimePermission(CLIENT_UID)
        verify(config).update(
            CLIENT_UID, listOf(client.packageName),
            ConfigManager.MASK_PERMISSION or ShizukuConfig.FLAG_PENDING_COMPANION, ShizukuConfig.FLAG_PENDING_COMPANION,
        )
        verify(userServices).removeUserServicesForPackage(client.packageName)
        assertFalse(client.allowed)
    }

    @Test
    fun androidRevocationWithCompanionPresentDoesNotBecomePending() {
        installed.requestedPermissions = arrayOf(ServerConstants.LEGACY_PERMISSION)
        companionAvailable()
        checkPermission(ServerConstants.LEGACY_PERMISSION, PackageManager.PERMISSION_DENIED)
        service.reconcileRuntimePermission(CLIENT_UID)
        verify(config).update(CLIENT_UID, null, ConfigManager.MASK_PERMISSION or ShizukuConfig.FLAG_PENDING_COMPANION, 0)
        assertFalse(client.allowed)
    }

    @Test
    fun togglingOffClearsPendingIntent() {
        pendingLegacy()
        ShadowBinder.setCallingUid(MANAGER_UID)
        service.updateFlagsForUid(CLIENT_UID, ConfigManager.MASK_PERMISSION, 0)
        verify(config).update(CLIENT_UID, listOf(client.packageName), ConfigManager.MASK_PERMISSION or ShizukuConfig.FLAG_PENDING_COMPANION, 0)
    }

    @Test
    fun pendingEntrySurvivesConfigSerializationWithoutBecomingEffective() {
        val entry = pendingLegacy()
        val gson = Gson()
        val restored = gson.fromJson(gson.toJson(entry), ShizukuConfig.PackageEntry::class.java)
        assertTrue(restored.isPendingCompanion())
        assertFalse(restored.isAllowed())
        assertEquals(listOf(client.packageName), restored.packages)
    }

    @Test
    fun temporaryPackageLookupFailurePreservesPendingIntent() {
        pendingLegacy()
        clientPackageMissing()
        service.reconcileRuntimePermission(CLIENT_UID)
        verify(config, never()).update(anyInt(), any(), anyInt(), anyInt())
        assertFalse(client.allowed)
    }

    @Test
    fun unresolvedRouteCannotCreateAnIncorrectPendingGrant() {
        ShadowBinder.setCallingUid(MANAGER_UID)
        clientPackageMissing()
        assertThrows(IllegalStateException::class.java) { service.updateFlagsForUid(CLIENT_UID, ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_ALLOWED) }
        verify(config, never()).update(anyInt(), any(), anyInt(), anyInt())
    }

    @Test
    fun unresolvedConsentRouteCompletesRequestWithoutSavingDenial() {
        ShadowBinder.setCallingUid(MANAGER_UID)
        clientPackageMissing()
        val record = spy(client)
        `when`(clients.findClients(CLIENT_UID)).thenReturn(listOf(record))
        val result = Bundle()
        result.putBoolean(ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED, true)
        service.endpoint.dispatchPermissionConfirmationResult(CLIENT_UID, CLIENT_PID, 42, result)
        verify(record).dispatchRequestPermissionResult(42, false)
        verify(config, never()).update(anyInt(), any(), anyInt(), anyInt())
        assertFalse(record.allowed)
    }

    @Test
    fun globalPauseBlocksEvenStaleAllowedRecordsAndRuntimePermissionFallback() {
        `when`(config.isAccessPaused).thenReturn(true)
        assertThrows(SecurityException::class.java) { service.core.enforceCallingPermission("transactRemote", CallerIdentity(CLIENT_UID, CLIENT_PID)) }
        `when`(clients.findClient(CLIENT_UID, CLIENT_PID)).thenReturn(null)
        activityMocks.`when`<Int> { ActivityManagerApis.checkPermission(ServerConstants.PERMISSION, CLIENT_PID, CLIENT_UID) }.thenReturn(PackageManager.PERMISSION_GRANTED)
        assertThrows(SecurityException::class.java) { service.core.enforceCallingPermission("newProcess", CallerIdentity(CLIENT_UID, CLIENT_PID)) }
        ShadowBinder.setCallingUid(MANAGER_UID)
        service.core.enforceCallingPermission("manager operation", CallerIdentity(MANAGER_UID, CLIENT_PID))
    }

    @Test
    fun pauseAndResumeKeepSavedDecisionsAndUpdateClients() {
        val paused = AtomicBoolean(false)
        `when`(config.isAccessPaused).thenAnswer { paused.get() }
        doAnswer { paused.set(it.getArgument(0)); null }.`when`(config).setAccessPaused(anyBoolean())
        `when`(clients.attachedClients()).thenReturn(listOf(client))
        val entry = config.find(CLIENT_UID)!!
        val before = entry.flags
        service.setGlobalAccess(false)
        assertFalse(client.allowed)
        verify(userServices).setAccessPaused(true)
        assertEquals(before, entry.flags)
        verify(config, never()).update(anyInt(), any(), anyInt(), anyInt())
        service.setGlobalAccess(true)
        assertTrue(client.allowed)
        assertEquals(before, entry.flags)
        verify(userServices).setAccessPaused(false)
        verify(client.client!!, times(2)).bindApplication(any(Bundle::class.java))
    }

    @Test
    fun permissionRequestWhilePausedReturnsDenialWithoutChangingConfig() {
        `when`(config.isAccessPaused).thenReturn(true)
        val record = spy(client)
        service.showPermissionConfirmation(17, record, CallerIdentity(CLIENT_UID, CLIENT_PID), 0)
        verify(record).dispatchRequestPermissionResult(17, false)
        verify(config, never()).update(anyInt(), any(), anyInt(), anyInt())
    }

    @Test
    fun managerGrantWhilePausedKeepsRecordMasked() {
        ShadowBinder.setCallingUid(MANAGER_UID)
        `when`(config.isAccessPaused).thenReturn(true)
        service.updateFlagsForUid(CLIENT_UID, ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_ALLOWED)
        assertFalse(client.allowed)
        verify(config).update(
            CLIENT_UID, listOf(client.packageName),
            ConfigManager.MASK_PERMISSION or ShizukuConfig.FLAG_PENDING_COMPANION, ConfigManager.FLAG_ALLOWED,
        )
    }

    @Test
    fun pendingActivationWhilePausedDoesNotEnableClient() {
        pendingLegacy()
        `when`(config.isAccessPaused).thenReturn(true)
        companionAvailable()
        checkPermission(ServerConstants.LEGACY_PERMISSION, PackageManager.PERMISSION_GRANTED)
        service.reconcileRuntimePermission(CLIENT_UID)
        assertFalse(client.allowed)
        verify(config).update(CLIENT_UID, null, ConfigManager.MASK_PERMISSION or ShizukuConfig.FLAG_PENDING_COMPANION, ConfigManager.FLAG_ALLOWED)
    }

    @Test
    fun persistedPauseDoesNotModifyPermissionEntries() {
        val saved = ShizukuConfig(mutableListOf(ShizukuConfig.PackageEntry(CLIENT_UID, ConfigManager.FLAG_ALLOWED)))
        saved.accessPaused = true
        val gson = Gson()
        val restored = gson.fromJson(gson.toJson(saved), ShizukuConfig::class.java)
        assertTrue(restored.accessPaused)
        assertTrue(restored.packages!![0].isAllowed())
        assertFalse(gson.fromJson("{\"version\":2}", ShizukuConfig::class.java).accessPaused)
    }

    @Test
    fun newlyAttachedClientIsMaskedWhenPersistedPauseIsActive() {
        val realClients = ShizukuClientManager(config)
        `when`(config.isAccessPaused).thenReturn(true)
        val callback = object : IShizukuApplication.Stub() {
            override fun bindApplication(data: Bundle?) {}
            override fun dispatchRequestPermissionResult(code: Int, data: Bundle?) {}
            override fun showPermissionConfirmation(uid: Int, pid: Int, name: String?, code: Int) {}
        }
        val record = realClients.attach(CallerIdentity(CLIENT_UID, CLIENT_PID), LegacyClientCallback(callback), client.packageName, 13)
        assertNotNull(record)
        assertFalse(record!!.allowed)
        assertEquals(listOf(record), realClients.attachedClients())
    }

    private fun porterClient(application: IPorterApplication): ClientRecord {
        val record = ClientRecord(CallerIdentity(CLIENT_UID, CLIENT_PID), PorterClientCallback(application), "test.client", 13)
        record.allowed = true
        `when`(clients.findClients(CLIENT_UID)).thenReturn(listOf(record))
        `when`(clients.findClient(CLIENT_UID, CLIENT_PID)).thenReturn(record)
        `when`(clients.attachedClients()).thenReturn(listOf(record))
        return record
    }

    private fun porterTransact(code: Int, arguments: (Parcel) -> Unit): Parcel {
        val request = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            request.writeInterfaceToken(PorterProtocol.DESCRIPTOR)
            arguments(request)
            request.setDataPosition(0)
            assertTrue(service.porterEndpoint.transact(code, request, reply, 0))
        } finally {
            request.recycle()
        }
        reply.setDataPosition(0)
        reply.readException()
        return reply
    }

    @Test
    fun everyAppTransactionIsAnsweredOnThePorterDescriptor() {
        ShadowBinder.setCallingUid(MANAGER_UID)
        service.reconciler = mock(ApkReconciler::class.java)
        val installedApp = installedApp(client.packageName, CLIENT_UID, ServerConstants.PERMISSION)
        mockStatic(UserManagerApis::class.java).use { users ->
            withPackageManager(packageManagerListing { user -> if (user == 0) listOf(installedApp) else emptyList() }) {
                mockStatic(OsUtils::class.java).use { os ->
                    users.`when`<List<Int>> { UserManagerApis.getUserIdsNoThrow() }.thenReturn(listOf(0))
                    os.`when`<Int> { OsUtils.getUid() }.thenReturn(MANAGER_UID)

                    val applications = porterTransact(ServerConstants.BINDER_TRANSACTION_getApplications) { it.writeInt(0) }
                    assertNotNull(ParcelableListSlice.CREATOR.createFromParcel(applications))
                    applications.recycle()

                    val diagnostics = porterTransact(ServerConstants.BINDER_TRANSACTION_getDiagnostics) {}
                    assertEquals(Process.myPid(), diagnostics.readInt())
                    assertEquals(
                        BuildConfig.PORTER_VERSION_NAME,
                        diagnostics.readBundle()!!.getString(ServerConstants.DIAGNOSTICS_VERSION_NAME),
                    )
                    diagnostics.recycle()

                    val discovery = porterTransact(DiscoveredApplication.TRANSACTION) { it.writeInt(0) }
                    assertEquals(DiscoveredApplication.WIRE_VERSION, discovery.readInt())
                    assertArrayEquals(IntArray(0), discovery.createIntArray())
                    discovery.recycle()

                    val access = porterTransact(GlobalAccess.TRANSACTION) { it.writeInt(GlobalAccess.READ) }
                    assertEquals(GlobalAccess.VERSION, access.readInt())
                    assertEquals(1, access.readInt())
                    access.recycle()

                    val setup = porterTransact(CompatibilitySetup.TRANSACTION) {
                        it.writeInt(CompatibilitySetup.INSPECT)
                        it.writeString(null)
                    }
                    assertEquals(CompatibilitySetup.VERSION, setup.readBundle()!!.getInt("version"))
                    setup.recycle()

                    val logging = porterTransact(ServerConstants.BINDER_TRANSACTION_setDebugLogging) {
                        it.writeStrongBinder(null)
                        it.writeLong(1000)
                    }
                    assertEquals(0L, logging.readLong())
                    logging.recycle()

                    `when`(userServices.isUserServiceTokenLive("token")).thenReturn(true)
                    val launch = porterTransact(UserServiceLaunch.TRANSACTION) { it.writeString("token") }
                    assertEquals(1, launch.readInt())
                    launch.recycle()
                }
            }
        }
    }

    @Test
    fun appTransactionsOnThePorterWireStillRequireTheirCaller() {
        for (code in intArrayOf(
            ServerConstants.BINDER_TRANSACTION_getDiagnostics, ServerConstants.BINDER_TRANSACTION_getApplications,
            ServerConstants.BINDER_TRANSACTION_setDebugLogging, DiscoveredApplication.TRANSACTION,
            GlobalAccess.TRANSACTION, CompatibilitySetup.TRANSACTION, UserServiceLaunch.TRANSACTION,
        )) {
            val request = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                request.writeInterfaceToken(PorterProtocol.DESCRIPTOR)
                request.setDataPosition(0)
                service.porterEndpoint.transact(code, request, reply, 0)
                reply.setDataPosition(0)
                assertThrows(SecurityException::class.java) { reply.readException() }
            } finally {
                request.recycle()
                reply.recycle()
            }
        }
    }

    /**
     * The Porter counterpart of [aGlobalPauseCannotOvertakeAnAttachReply]: the reply is the
     * delivery here, so the endpoint must hold the monitor until it has been built and handed back.
     */
    @Test
    fun aGlobalPauseCannotOvertakeAPorterAttachReply() {
        val record = porterClient(mock(IPorterApplication::class.java))
        `when`(clients.findClient(CLIENT_UID, CLIENT_PID)).thenReturn(null)
        `when`(clients.attach(anyK(CallerIdentity::class.java), anyK(ClientCallback::class.java), eqK(record.packageName), anyInt())).thenReturn(record)
        `when`(clients.attachedClients()).thenReturn(listOf())
        installed.applicationInfo = ApplicationInfo().also { it.uid = CLIENT_UID }
        var pause: Thread? = null
        val pauseStillBlocked = AtomicBoolean()
        doAnswer {
            val thread = Thread { service.setGlobalAccess(false) }
            pause = thread
            thread.start()
            thread.join(500)
            pauseStillBlocked.set(thread.isAlive)
            null
        }.`when`(history).connected(any(), anyLong())
        val args = Bundle()
        args.putString(PorterProtocol.ATTACH_PACKAGE_NAME, record.packageName)
        service.porterEndpoint.attach(mock(IPorterApplication::class.java), args)
        assertTrue(pauseStillBlocked.get())
        pause!!.join(500)
    }

    @Test
    fun aPausedPorterClientIsToldAndRefusedUntilAccessResumes() {
        val application = mock(IPorterApplication::class.java)
        `when`(application.asBinder()).thenReturn(Binder())
        val record = porterClient(application)
        val paused = AtomicBoolean(false)
        `when`(config.isAccessPaused).thenAnswer { paused.get() }
        doAnswer { paused.set(it.getArgument(0)); null }.`when`(config).setAccessPaused(anyBoolean())

        service.setGlobalAccess(false)
        assertFalse(record.allowed)
        val state = ArgumentCaptor.forClass(Bundle::class.java)
        verify(application).dispatchPermissionStateChanged(state.capture())
        assertFalse(state.value.getBoolean(PorterProtocol.REPLY_PERMISSION_GRANTED))
        assertThrows(SecurityException::class.java) { service.core.enforceCallingPermission("transactRemote", CallerIdentity(CLIENT_UID, CLIENT_PID)) }

        service.setGlobalAccess(true)
        assertTrue(record.allowed)
        verify(application, times(2)).dispatchPermissionStateChanged(state.capture())
        assertTrue(state.value.getBoolean(PorterProtocol.REPLY_PERMISSION_GRANTED))
        service.core.enforceCallingPermission("transactRemote", CallerIdentity(CLIENT_UID, CLIENT_PID))
    }

    private companion object {
        const val MANAGER_UID = 10100
        const val CLIENT_UID = 10200
        const val CLIENT_PID = 45678
    }
}
