package eu.darken.porter.privileged;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Parcel;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBinder;
import rikka.hidden.compat.PackageManagerApis;
import rikka.hidden.compat.ActivityManagerApis;
import rikka.shizuku.ShizukuApiConstants;
import rikka.shizuku.server.legacy.LegacyClientCallback;
import eu.darken.porter.privileged.util.Android17Compat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import eu.darken.porter.core.CallerIdentity;
import eu.darken.porter.core.ClientCallback;
import eu.darken.porter.endpoint.PorterClientCallback;
import eu.darken.porter.protocol.PorterProtocol;
import eu.darken.porter.server.IPorterApplication;
import moe.shizuku.server.IShizukuApplication;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import rikka.shizuku.server.ClientRecord;
import rikka.shizuku.server.ConfigManager;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class ServiceAuthorizationTest {
    private static final int MANAGER_UID = 10100;
    private static final int CLIENT_UID = 10200;
    private static final int CLIENT_PID = 45678;
    private PorterServer service;
    private ShizukuConfigManager config;
    private ShizukuClientManager clients;
    private ShizukuUserServiceManager userServices;
    private ConnectionHistory history;
    private ClientRecord client;
    private PackageInfo installed;
    private MockedStatic<PackageManagerApis> packages;
    private MockedStatic<Android17Compat> permissions;
    private MockedStatic<Compatibility> companion;
    private MockedStatic<ActivityManagerApis> activities;

    @Before public void setup() throws Exception {
        config = mock(ShizukuConfigManager.class);
        clients = mock(ShizukuClientManager.class);
        userServices = mock(ShizukuUserServiceManager.class);
        history = mock(ConnectionHistory.class);
        service = new PorterServer(userServices, clients, config, MANAGER_UID, history,
                Runnable::run, ShizukuServiceEndpoint::new, PorterServiceEndpoint::new);
        client = new ClientRecord(CLIENT_UID, CLIENT_PID, mock(IShizukuApplication.class), "test.client", 13);
        client.allowed = true;
        when(clients.findClients(CLIENT_UID)).thenReturn(List.of(client));
        when(clients.findClient(CLIENT_UID, CLIENT_PID)).thenReturn(client);
        when(config.find(CLIENT_UID)).thenReturn(new ShizukuConfig.PackageEntry(CLIENT_UID, ConfigManager.FLAG_ALLOWED));
        installed = new PackageInfo();
        installed.packageName = client.packageName;
        installed.requestedPermissions = new String[]{ServerConstants.PERMISSION};
        packages = mockStatic(PackageManagerApis.class);
        packages.when(() -> PackageManagerApis.getPackagesForUidNoThrow(CLIENT_UID)).thenReturn(List.of(client.packageName));
        permissions = mockStatic(Android17Compat.class);
        permissions.when(() -> Android17Compat.getPackageInfo(eq(client.packageName), anyLong(), eq(0))).thenReturn(installed);
        activities = mockStatic(ActivityManagerApis.class);
        companion = mockStatic(Compatibility.class);
        companion.when(Compatibility::isAvailable).thenReturn(false);
        ShadowBinder.setCallingUid(CLIENT_UID);
        ShadowBinder.setCallingPid(CLIENT_PID);
    }

    @After public void close() {
        activities.close();
        companion.close();
        permissions.close();
        packages.close();
        ShadowBinder.reset();
    }

    @Test public void grantedClientStillCannotInvokeManagerOperations() {
        assertThrows(SecurityException.class, () -> service.getEndpoint().getFlagsForUid(CLIENT_UID, ConfigManager.MASK_PERMISSION));
        assertThrows(SecurityException.class, () -> service.getEndpoint().updateFlagsForUid(CLIENT_UID, ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_ALLOWED));
        assertThrows(SecurityException.class, () -> service.getEndpoint().dispatchPermissionConfirmationResult(CLIENT_UID, CLIENT_PID, 1, new Bundle()));
        assertThrows(SecurityException.class, () -> service.getEndpoint().attachUserService(null, new Bundle()));
        assertThrows(SecurityException.class, service.getEndpoint()::exit);
        verifyNoInteractions(config, userServices);
    }

    @Test public void diagnosticsAndApplicationsTransactionsRequireManager() {
        for (int code : new int[]{ServerConstants.BINDER_TRANSACTION_getDiagnostics, ServerConstants.BINDER_TRANSACTION_getApplications, eu.darken.porter.common.DiscoveredApplication.TRANSACTION, eu.darken.porter.common.GlobalAccess.TRANSACTION, eu.darken.porter.common.CompatibilitySetup.TRANSACTION}) {
            Parcel request = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                request.writeInterfaceToken(ShizukuApiConstants.BINDER_DESCRIPTOR);
                request.setDataPosition(0);
                assertThrows(SecurityException.class, () -> service.getEndpoint().onTransact(code, request, reply, 0));
            } finally { request.recycle(); reply.recycle(); }
        }
    }

    @Test public void managerUidCanReadFlagsButOtherAppsCannot() {
        ShadowBinder.setCallingUid(MANAGER_UID);
        assertEquals(ConfigManager.FLAG_ALLOWED, service.getEndpoint().getFlagsForUid(CLIENT_UID, ConfigManager.MASK_PERMISSION));
        ShadowBinder.setCallingUid(CLIENT_UID);
        assertThrows(SecurityException.class, () -> service.getEndpoint().getFlagsForUid(CLIENT_UID, ConfigManager.MASK_PERMISSION));
    }

    @Test public void permissionRevocationDropsGrantAndTerminatesUserServices() throws Exception {
        permissions.when(() -> Android17Compat.checkPermission(ServerConstants.PERMISSION, client.packageName, 0)).thenReturn(PackageManager.PERMISSION_DENIED);
        service.reconcileRuntimePermission(CLIENT_UID);
        assertFalse(client.allowed);
        verify(config).update(CLIENT_UID, null, ConfigManager.MASK_PERMISSION | ShizukuConfig.FLAG_PENDING_COMPANION, 0);
        verify(userServices).removeUserServicesForPackage(client.packageName);
        assertThrows(SecurityException.class, () -> service.getCore().enforceCallingPermission("client operation", new CallerIdentity(CLIENT_UID, CLIENT_PID)));
    }

    @Test public void existingGrantSurvivesWhilePrimaryRuntimePermissionIsGranted() throws Exception {
        permissions.when(() -> Android17Compat.checkPermission(ServerConstants.PERMISSION, client.packageName, 0)).thenReturn(PackageManager.PERMISSION_GRANTED);
        service.reconcileRuntimePermission(CLIENT_UID);
        assertTrue(client.allowed);
        verify(config, never()).update(anyInt(), any(), anyInt(), anyInt());
        verifyNoInteractions(userServices);
        service.getCore().enforceCallingPermission("client operation", new CallerIdentity(CLIENT_UID, CLIENT_PID));
    }

    @Test public void legacyGrantRequiresCompanionEvenWhenAndroidPermissionRemainsGranted() throws Exception {
        installed.requestedPermissions = new String[]{ServerConstants.LEGACY_PERMISSION};
        permissions.when(() -> Android17Compat.checkPermission(ServerConstants.LEGACY_PERMISSION, client.packageName, 0)).thenReturn(PackageManager.PERMISSION_GRANTED);
        service.reconcileRuntimePermission(CLIENT_UID);
        assertFalse(client.allowed);
        verify(userServices).removeUserServicesForPackage(client.packageName);
    }

    @Test public void trustedLegacyGrantSurvives() throws Exception {
        installed.requestedPermissions = new String[]{ServerConstants.LEGACY_PERMISSION};
        companion.when(Compatibility::isAvailable).thenReturn(true);
        permissions.when(() -> Android17Compat.checkPermission(ServerConstants.LEGACY_PERMISSION, client.packageName, 0)).thenReturn(PackageManager.PERMISSION_GRANTED);
        service.reconcileRuntimePermission(CLIENT_UID);
        assertTrue(client.allowed);
        verifyNoInteractions(userServices);
    }

    @Test public void legacyGrantCannotOverrideRevokedPorterPermissionForDualClient() throws Exception {
        installed.requestedPermissions = new String[]{ServerConstants.PERMISSION, ServerConstants.LEGACY_PERMISSION};
        companion.when(Compatibility::isAvailable).thenReturn(true);
        permissions.when(() -> Android17Compat.checkPermission(ServerConstants.PERMISSION, client.packageName, 0)).thenReturn(PackageManager.PERMISSION_DENIED);
        permissions.when(() -> Android17Compat.checkPermission(ServerConstants.LEGACY_PERMISSION, client.packageName, 0)).thenReturn(PackageManager.PERMISSION_GRANTED);
        service.reconcileRuntimePermission(CLIENT_UID);
        assertFalse(client.allowed);
        verify(userServices).removeUserServicesForPackage(client.packageName);
    }

    @Test public void terminalClientWithoutRuntimePermissionKeepsExplicitConsent() throws Exception {
        installed.requestedPermissions = null;
        service.reconcileRuntimePermission(CLIENT_UID);
        assertTrue(client.allowed);
        verifyNoInteractions(userServices);
    }

    @Test public void grantingPorterAccessDoesNotTouchLegacyPermissionWithoutCompanion() throws Exception {
        ShadowBinder.setCallingUid(MANAGER_UID);
        installed.requestedPermissions = new String[]{ServerConstants.PERMISSION, ServerConstants.LEGACY_PERMISSION};
        service.updateFlagsForUid(CLIENT_UID, ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_ALLOWED);
        verify(config).update(CLIENT_UID, List.of(client.packageName), ConfigManager.MASK_PERMISSION | ShizukuConfig.FLAG_PENDING_COMPANION, ConfigManager.FLAG_ALLOWED);
        permissions.verify(() -> Android17Compat.grantRuntimePermission(client.packageName, ServerConstants.PERMISSION, 0));
        permissions.verify(() -> Android17Compat.grantRuntimePermission(client.packageName, ServerConstants.LEGACY_PERMISSION, 0), never());
    }

    @Test public void explicitRevocationStopsAttachedClientAndItsUserServices() throws Exception {
        ShadowBinder.setCallingUid(MANAGER_UID);
        service.updateFlagsForUid(CLIENT_UID, ConfigManager.MASK_PERMISSION, 0);
        assertFalse(client.allowed);
        activities.verify(() -> ActivityManagerApis.forceStopPackageNoThrow(client.packageName, 0));
        permissions.verify(() -> Android17Compat.revokeRuntimePermission(client.packageName, ServerConstants.PERMISSION, 0));
        verify(userServices).removeUserServicesForPackage(client.packageName);
        verify(config).update(CLIENT_UID, List.of(client.packageName), ConfigManager.MASK_PERMISSION | ShizukuConfig.FLAG_PENDING_COMPANION, 0);
    }

    @Test public void explicitRevocationAlsoStopsDaemonWithoutAttachedClient() throws Exception {
        ShadowBinder.setCallingUid(MANAGER_UID);
        when(clients.findClients(CLIENT_UID)).thenReturn(List.of());
        service.updateFlagsForUid(CLIENT_UID, ConfigManager.MASK_PERMISSION, 0);
        verify(userServices).removeUserServicesForPackage(client.packageName);
        permissions.verify(() -> Android17Compat.revokeRuntimePermission(client.packageName, ServerConstants.PERMISSION, 0));
    }

    @Test public void managerPackagesNeverReceiveClientBinder() {
        android.content.pm.PermissionInfo managerPermission = new android.content.pm.PermissionInfo();
        managerPermission.name = "foreign.fork.permission.MANAGER";
        installed.permissions = new android.content.pm.PermissionInfo[]{managerPermission};
        assertNull(PorterServer.route(installed));
    }
    @Test public void acceptedNewConnectionIsRecordedWithoutGrantingAccess() throws Exception {
        when(clients.findClient(CLIENT_UID, CLIENT_PID)).thenReturn(null);
        when(clients.attach(any(CallerIdentity.class), any(ClientCallback.class), eq(client.packageName), anyInt())).thenReturn(client);
        installed.applicationInfo = new android.content.pm.ApplicationInfo();
        installed.applicationInfo.uid = CLIENT_UID;
        installed.firstInstallTime = 100;
        Bundle args = new Bundle();
        args.putString(ShizukuApiConstants.ATTACH_APPLICATION_PACKAGE_NAME, client.packageName);
        var callback = mock(IShizukuApplication.class);
        service.getEndpoint().attachApplication(callback, args);
        verify(callback).bindApplication(any());
        verify(history).connected(eq(installed), anyLong());
        verify(config, never()).update(anyInt(), any(), anyInt(), anyInt());
    }

    @Test public void failedConnectionReplyDoesNotCreateHistory() throws Exception {
        when(clients.findClient(CLIENT_UID, CLIENT_PID)).thenReturn(null);
        when(clients.attach(any(CallerIdentity.class), any(ClientCallback.class), eq(client.packageName), anyInt())).thenReturn(client);
        Bundle args = new Bundle();
        args.putString(ShizukuApiConstants.ATTACH_APPLICATION_PACKAGE_NAME, client.packageName);
        var callback = mock(IShizukuApplication.class);
        doThrow(new android.os.RemoteException()).when(callback).bindApplication(any());
        service.getEndpoint().attachApplication(callback, args);
        verifyNoInteractions(history);
    }

    /**
     * Without the client-manager monitor the endpoint holds across an attach transaction, a pause
     * landing between the record's creation and its reply overtakes that reply: the client is told
     * it is denied and then told it is granted, and believes the later answer.
     */
    @Test public void aGlobalPauseCannotOvertakeAnAttachReply() throws Exception {
        when(clients.findClient(CLIENT_UID, CLIENT_PID)).thenReturn(null);
        when(clients.attach(any(CallerIdentity.class), any(ClientCallback.class), eq(client.packageName), anyInt())).thenReturn(client);
        Thread[] pause = new Thread[1];
        AtomicBoolean pauseStillBlocked = new AtomicBoolean();
        var callback = new IShizukuApplication.Stub() {
            public void bindApplication(Bundle data) {
                pause[0] = new Thread(() -> service.setGlobalAccess(false));
                pause[0].start();
                try { pause[0].join(500); } catch (InterruptedException ignored) {}
                pauseStillBlocked.set(pause[0].isAlive());
            }
            public void dispatchRequestPermissionResult(int code, Bundle data) {}
            public void showPermissionConfirmation(int uid, int pid, String name, int code) {}
        };
        Bundle args = new Bundle();
        args.putString(ShizukuApiConstants.ATTACH_APPLICATION_PACKAGE_NAME, client.packageName);
        Parcel request = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            request.writeInterfaceToken(ShizukuApiConstants.BINDER_DESCRIPTOR);
            request.writeStrongBinder(callback.asBinder());
            request.writeInt(1);
            args.writeToParcel(request, 0);
            request.setDataPosition(0);
            assertTrue(service.getEndpoint().onTransact(Binder.FIRST_CALL_TRANSACTION + 17, request, reply, 0));
        } finally { request.recycle(); reply.recycle(); }
        assertTrue(pauseStillBlocked.get());
        pause[0].join(500);
    }

    @Test public void discoveryKeepsOtherAppsAfterOversizedEntryAndPreservesFailedProfileHistory() throws Exception {
        ShadowBinder.setCallingUid(MANAGER_UID);
        var nativeApp = ApplicationDiscoveryTest.app("native", CLIENT_UID, ServerConstants.PERMISSION);
        var oversized = ApplicationDiscoveryTest.app("x".repeat(30000), CLIENT_UID + 1, ServerConstants.PERMISSION);
        var legacy = ApplicationDiscoveryTest.app("legacy", CLIENT_UID + 2, ServerConstants.LEGACY_PERMISSION);
        var ownerPackages = List.of(nativeApp, oversized, legacy);
        try (var users = mockStatic(rikka.hidden.compat.UserManagerApis.class);
             var installedApps = mockStatic(eu.darken.porter.privileged.util.InstalledPackagesCompat.class)) {
            users.when(rikka.hidden.compat.UserManagerApis::getUserIdsNoThrow).thenReturn(List.of(0, 10));
            installedApps.when(() -> eu.darken.porter.privileged.util.InstalledPackagesCompat.getInstalledPackages(anyLong(), eq(0))).thenReturn(ownerPackages);
            installedApps.when(() -> eu.darken.porter.privileged.util.InstalledPackagesCompat.getInstalledPackages(anyLong(), eq(10))).thenThrow(new SecurityException("profile unavailable"));
            Parcel request = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                request.writeInterfaceToken(ShizukuApiConstants.BINDER_DESCRIPTOR);
                request.writeInt(-1);
                request.setDataPosition(0);
                assertTrue(service.getEndpoint().onTransact(eu.darken.porter.common.DiscoveredApplication.TRANSACTION, request, reply, 0));
                reply.setDataPosition(0);
                reply.readException();
                assertEquals(eu.darken.porter.common.DiscoveredApplication.WIRE_VERSION, reply.readInt());
                assertArrayEquals(new int[]{0, 10}, reply.createIntArray());
                @SuppressWarnings("unchecked")
                var apps = ((rikka.parcelablelist.ParcelableListSlice<eu.darken.porter.common.DiscoveredApplication>) rikka.parcelablelist.ParcelableListSlice.CREATOR.createFromParcel(reply)).getList();
                assertEquals(2, apps.size());
                assertEquals("native", apps.get(0).applicationInfo.packageName);
                assertEquals(eu.darken.porter.common.DiscoveredApplication.ALLOWED, apps.get(0).authorization);
                assertEquals(eu.darken.porter.common.DiscoveredApplication.NEEDS_COMPANION, apps.get(1).connectionStatus);
                verify(history).pruneUser(eq(0), eq(ownerPackages), anyLong());
                verify(history, never()).pruneUser(eq(10), anyList(), anyLong());
            } finally { request.recycle(); reply.recycle(); }
        }
    }


    private ShizukuConfig.PackageEntry pendingLegacy() {
        installed.requestedPermissions = new String[]{ServerConstants.LEGACY_PERMISSION};
        var entry = new ShizukuConfig.PackageEntry(CLIENT_UID, ShizukuConfig.FLAG_PENDING_COMPANION);
        entry.packages.add(client.packageName);
        when(config.find(CLIENT_UID)).thenReturn(entry);
        client.allowed = false;
        return entry;
    }

    @Test public void missingCompanionStoresIntentWithoutGrantingBinderAccess() throws Exception {
        installed.requestedPermissions = new String[]{ServerConstants.LEGACY_PERMISSION};
        ShadowBinder.setCallingUid(MANAGER_UID);
        service.updateFlagsForUid(CLIENT_UID, ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_ALLOWED);
        verify(config).update(CLIENT_UID, List.of(client.packageName),
                ConfigManager.MASK_PERMISSION | ShizukuConfig.FLAG_PENDING_COMPANION, ShizukuConfig.FLAG_PENDING_COMPANION);
        permissions.verify(() -> Android17Compat.grantRuntimePermission(anyString(), anyString(), anyInt()), never());
        assertFalse(client.allowed);
        ShadowBinder.setCallingUid(CLIENT_UID);
        assertThrows(SecurityException.class, () -> service.getCore().enforceCallingPermission("client operation", new CallerIdentity(CLIENT_UID, CLIENT_PID)));
    }

    @Test public void pendingAccessActivatesOnlyAfterTrustedCompanionAndVerifiedRuntimeGrant() throws Exception {
        var entry = pendingLegacy();
        assertFalse(entry.isAllowed());
        assertFalse(entry.isDenied());
        service.reconcileRuntimePermission(CLIENT_UID);
        assertFalse(client.allowed);
        companion.when(Compatibility::isAvailable).thenReturn(true);
        permissions.when(() -> Android17Compat.checkPermission(ServerConstants.LEGACY_PERMISSION, client.packageName, 0)).thenReturn(PackageManager.PERMISSION_GRANTED);
        service.reconcileRuntimePermission(CLIENT_UID);
        permissions.verify(() -> Android17Compat.grantRuntimePermission(client.packageName, ServerConstants.LEGACY_PERMISSION, 0));
        verify(config).update(CLIENT_UID, null, ConfigManager.MASK_PERMISSION | ShizukuConfig.FLAG_PENDING_COMPANION, ConfigManager.FLAG_ALLOWED);
        assertTrue(client.allowed);
    }

    @Test public void failedRuntimeGrantDoesNotActivatePendingAccess() throws Exception {
        pendingLegacy();
        companion.when(Compatibility::isAvailable).thenReturn(true);
        permissions.when(() -> Android17Compat.checkPermission(ServerConstants.LEGACY_PERMISSION, client.packageName, 0)).thenReturn(PackageManager.PERMISSION_DENIED);
        service.reconcileRuntimePermission(CLIENT_UID);
        assertFalse(client.allowed);
        verify(config, never()).update(anyInt(), any(), anyInt(), eq(ConfigManager.FLAG_ALLOWED));
    }

    @Test public void removingCompanionSuspendsAccessButKeepsIntent() throws Exception {
        installed.requestedPermissions = new String[]{ServerConstants.LEGACY_PERMISSION};
        service.reconcileRuntimePermission(CLIENT_UID);
        verify(config).update(CLIENT_UID, List.of(client.packageName),
                ConfigManager.MASK_PERMISSION | ShizukuConfig.FLAG_PENDING_COMPANION, ShizukuConfig.FLAG_PENDING_COMPANION);
        verify(userServices).removeUserServicesForPackage(client.packageName);
        assertFalse(client.allowed);
    }

    @Test public void androidRevocationWithCompanionPresentDoesNotBecomePending() throws Exception {
        installed.requestedPermissions = new String[]{ServerConstants.LEGACY_PERMISSION};
        companion.when(Compatibility::isAvailable).thenReturn(true);
        permissions.when(() -> Android17Compat.checkPermission(ServerConstants.LEGACY_PERMISSION, client.packageName, 0)).thenReturn(PackageManager.PERMISSION_DENIED);
        service.reconcileRuntimePermission(CLIENT_UID);
        verify(config).update(CLIENT_UID, null, ConfigManager.MASK_PERMISSION | ShizukuConfig.FLAG_PENDING_COMPANION, 0);
        assertFalse(client.allowed);
    }

    @Test public void togglingOffClearsPendingIntent() throws Exception {
        pendingLegacy();
        ShadowBinder.setCallingUid(MANAGER_UID);
        service.updateFlagsForUid(CLIENT_UID, ConfigManager.MASK_PERMISSION, 0);
        verify(config).update(CLIENT_UID, List.of(client.packageName), ConfigManager.MASK_PERMISSION | ShizukuConfig.FLAG_PENDING_COMPANION, 0);
    }

    @Test public void pendingEntrySurvivesConfigSerializationWithoutBecomingEffective() {
        var entry = pendingLegacy();
        var gson = new com.google.gson.Gson();
        var restored = gson.fromJson(gson.toJson(entry), ShizukuConfig.PackageEntry.class);
        assertTrue(restored.isPendingCompanion());
        assertFalse(restored.isAllowed());
        assertEquals(List.of(client.packageName), restored.packages);
    }
    @Test public void temporaryPackageLookupFailurePreservesPendingIntent() throws Exception {
        pendingLegacy();
        permissions.when(() -> Android17Compat.getPackageInfo(eq(client.packageName), anyLong(), eq(0))).thenReturn(null);
        service.reconcileRuntimePermission(CLIENT_UID);
        verify(config, never()).update(anyInt(), any(), anyInt(), anyInt());
        assertFalse(client.allowed);
    }
    @Test public void unresolvedRouteCannotCreateAnIncorrectPendingGrant() {
        ShadowBinder.setCallingUid(MANAGER_UID);
        permissions.when(() -> Android17Compat.getPackageInfo(eq(client.packageName), anyLong(), eq(0))).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> service.updateFlagsForUid(CLIENT_UID, ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_ALLOWED));
        verify(config, never()).update(anyInt(), any(), anyInt(), anyInt());
    }
    @Test public void unresolvedConsentRouteCompletesRequestWithoutSavingDenial() throws Exception {
        ShadowBinder.setCallingUid(MANAGER_UID);
        permissions.when(() -> Android17Compat.getPackageInfo(eq(client.packageName), anyLong(), eq(0))).thenReturn(null);
        var record = spy(client);
        when(clients.findClients(CLIENT_UID)).thenReturn(List.of(record));
        var result = new Bundle();
        result.putBoolean(ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED, true);
        service.getEndpoint().dispatchPermissionConfirmationResult(CLIENT_UID, CLIENT_PID, 42, result);
        verify(record).dispatchRequestPermissionResult(42, false);
        verify(config, never()).update(anyInt(), any(), anyInt(), anyInt());
        assertFalse(record.allowed);
    }

    @Test public void globalPauseBlocksEvenStaleAllowedRecordsAndRuntimePermissionFallback() {
        when(config.isAccessPaused()).thenReturn(true);
        assertThrows(SecurityException.class, () -> service.getCore().enforceCallingPermission("transactRemote", new CallerIdentity(CLIENT_UID, CLIENT_PID)));
        when(clients.findClient(CLIENT_UID, CLIENT_PID)).thenReturn(null);
        activities.when(() -> ActivityManagerApis.checkPermission(ServerConstants.PERMISSION, CLIENT_PID, CLIENT_UID)).thenReturn(PackageManager.PERMISSION_GRANTED);
        assertThrows(SecurityException.class, () -> service.getCore().enforceCallingPermission("newProcess", new CallerIdentity(CLIENT_UID, CLIENT_PID)));
        ShadowBinder.setCallingUid(MANAGER_UID);
        service.getCore().enforceCallingPermission("manager operation", new CallerIdentity(MANAGER_UID, CLIENT_PID));
    }

    @Test public void pauseAndResumeKeepSavedDecisionsAndUpdateClients() throws Exception {
        var paused = new java.util.concurrent.atomic.AtomicBoolean(false);
        when(config.isAccessPaused()).thenAnswer(inv -> paused.get());
        doAnswer(inv -> { paused.set(inv.getArgument(0)); return null; }).when(config).setAccessPaused(anyBoolean());
        when(clients.attachedClients()).thenReturn(List.of(client));
        var entry = config.find(CLIENT_UID);
        int before = entry.flags;
        service.setGlobalAccess(false);
        assertFalse(client.allowed);
        verify(userServices).setAccessPaused(true);
        assertEquals(before, entry.flags);
        verify(config, never()).update(anyInt(), any(), anyInt(), anyInt());
        service.setGlobalAccess(true);
        assertTrue(client.allowed);
        assertEquals(before, entry.flags);
        verify(userServices).setAccessPaused(false);
        verify(client.client, times(2)).bindApplication(any(Bundle.class));
    }

    @Test public void permissionRequestWhilePausedReturnsDenialWithoutChangingConfig() {
        when(config.isAccessPaused()).thenReturn(true);
        var record = spy(client);
        service.showPermissionConfirmation(17, record, new CallerIdentity(CLIENT_UID, CLIENT_PID), 0);
        verify(record).dispatchRequestPermissionResult(17, false);
        verify(config, never()).update(anyInt(), any(), anyInt(), anyInt());
    }

    @Test public void managerGrantWhilePausedKeepsRecordMasked() throws Exception {
        ShadowBinder.setCallingUid(MANAGER_UID);
        when(config.isAccessPaused()).thenReturn(true);
        service.updateFlagsForUid(CLIENT_UID, ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_ALLOWED);
        assertFalse(client.allowed);
        verify(config).update(CLIENT_UID, List.of(client.packageName),
            ConfigManager.MASK_PERMISSION | ShizukuConfig.FLAG_PENDING_COMPANION, ConfigManager.FLAG_ALLOWED);
    }

    @Test public void pendingActivationWhilePausedDoesNotEnableClient() throws Exception {
        pendingLegacy();
        when(config.isAccessPaused()).thenReturn(true);
        companion.when(Compatibility::isAvailable).thenReturn(true);
        permissions.when(() -> Android17Compat.checkPermission(ServerConstants.LEGACY_PERMISSION, client.packageName, 0)).thenReturn(PackageManager.PERMISSION_GRANTED);
        service.reconcileRuntimePermission(CLIENT_UID);
        assertFalse(client.allowed);
        verify(config).update(CLIENT_UID, null, ConfigManager.MASK_PERMISSION | ShizukuConfig.FLAG_PENDING_COMPANION, ConfigManager.FLAG_ALLOWED);
    }

    @Test public void persistedPauseDoesNotModifyPermissionEntries() {
        var saved = new ShizukuConfig(List.of(new ShizukuConfig.PackageEntry(CLIENT_UID, ConfigManager.FLAG_ALLOWED)));
        saved.accessPaused = true;
        var gson = new com.google.gson.Gson();
        var restored = gson.fromJson(gson.toJson(saved), ShizukuConfig.class);
        assertTrue(restored.accessPaused);
        assertTrue(restored.packages.get(0).isAllowed());
        assertFalse(gson.fromJson("{\"version\":2}", ShizukuConfig.class).accessPaused);
    }

    @Test public void newlyAttachedClientIsMaskedWhenPersistedPauseIsActive() {
        var realClients = new ShizukuClientManager(config);
        when(config.isAccessPaused()).thenReturn(true);
        var callback = new IShizukuApplication.Stub() {
            public void bindApplication(Bundle data) {}
            public void dispatchRequestPermissionResult(int code, Bundle data) {}
            public void showPermissionConfirmation(int uid, int pid, String name, int code) {}
        };
        var record = realClients.attach(new CallerIdentity(CLIENT_UID, CLIENT_PID), new LegacyClientCallback(callback), client.packageName, 13);
        assertNotNull(record);
        assertFalse(record.allowed);
        assertEquals(List.of(record), realClients.attachedClients());
    }

    private ClientRecord porterClient(IPorterApplication application) {
        var record = new ClientRecord(new CallerIdentity(CLIENT_UID, CLIENT_PID),
                new PorterClientCallback(application), "test.client", 13);
        record.allowed = true;
        when(clients.findClients(CLIENT_UID)).thenReturn(List.of(record));
        when(clients.findClient(CLIENT_UID, CLIENT_PID)).thenReturn(record);
        when(clients.attachedClients()).thenReturn(List.of(record));
        return record;
    }

    private Parcel porterTransact(int code, java.util.function.Consumer<Parcel> arguments) throws Exception {
        Parcel request = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            request.writeInterfaceToken(PorterProtocol.DESCRIPTOR);
            arguments.accept(request);
            request.setDataPosition(0);
            assertTrue(service.getPorterEndpoint().onTransact(code, request, reply, 0));
        } finally { request.recycle(); }
        reply.setDataPosition(0);
        reply.readException();
        return reply;
    }

    @Test public void everyAppTransactionIsAnsweredOnThePorterDescriptor() throws Exception {
        ShadowBinder.setCallingUid(MANAGER_UID);
        service.reconciler = mock(ApkReconciler.class);
        var installedApp = ApplicationDiscoveryTest.app(client.packageName, CLIENT_UID, ServerConstants.PERMISSION);
        try (var users = mockStatic(rikka.hidden.compat.UserManagerApis.class);
             var installedApps = mockStatic(eu.darken.porter.privileged.util.InstalledPackagesCompat.class);
             var os = mockStatic(moe.shizuku.common.util.OsUtils.class)) {
            users.when(rikka.hidden.compat.UserManagerApis::getUserIdsNoThrow).thenReturn(List.of(0));
            installedApps.when(() -> eu.darken.porter.privileged.util.InstalledPackagesCompat.getInstalledPackages(anyLong(), eq(0))).thenReturn(List.of(installedApp));
            installedApps.when(() -> eu.darken.porter.privileged.util.InstalledPackagesCompat.getInstalledPackagesNoThrow(anyLong(), eq(0))).thenReturn(List.of(installedApp));
            os.when(moe.shizuku.common.util.OsUtils::getUid).thenReturn(MANAGER_UID);

            Parcel applications = porterTransact(ServerConstants.BINDER_TRANSACTION_getApplications, data -> data.writeInt(0));
            assertNotNull(rikka.parcelablelist.ParcelableListSlice.CREATOR.createFromParcel(applications));
            applications.recycle();

            Parcel diagnostics = porterTransact(ServerConstants.BINDER_TRANSACTION_getDiagnostics, data -> {});
            assertEquals(android.os.Process.myPid(), diagnostics.readInt());
            assertEquals(moe.shizuku.server.BuildConfig.PORTER_VERSION_NAME,
                    diagnostics.readBundle().getString(ServerConstants.DIAGNOSTICS_VERSION_NAME));
            diagnostics.recycle();

            Parcel discovery = porterTransact(eu.darken.porter.common.DiscoveredApplication.TRANSACTION, data -> data.writeInt(0));
            assertEquals(eu.darken.porter.common.DiscoveredApplication.WIRE_VERSION, discovery.readInt());
            assertArrayEquals(new int[0], discovery.createIntArray());
            discovery.recycle();

            Parcel access = porterTransact(eu.darken.porter.common.GlobalAccess.TRANSACTION,
                    data -> data.writeInt(eu.darken.porter.common.GlobalAccess.READ));
            assertEquals(eu.darken.porter.common.GlobalAccess.VERSION, access.readInt());
            assertEquals(1, access.readInt());
            access.recycle();

            Parcel setup = porterTransact(eu.darken.porter.common.CompatibilitySetup.TRANSACTION, data -> {
                data.writeInt(eu.darken.porter.common.CompatibilitySetup.INSPECT);
                data.writeString(null);
            });
            assertEquals(eu.darken.porter.common.CompatibilitySetup.VERSION, setup.readBundle().getInt("version"));
            setup.recycle();

            Parcel logging = porterTransact(ServerConstants.BINDER_TRANSACTION_setDebugLogging, data -> {
                data.writeStrongBinder(null);
                data.writeLong(1000);
            });
            assertEquals(0L, logging.readLong());
            logging.recycle();

            when(userServices.isUserServiceTokenLive("token")).thenReturn(true);
            Parcel launch = porterTransact(eu.darken.porter.common.UserServiceLaunch.TRANSACTION,
                    data -> data.writeString("token"));
            assertEquals(1, launch.readInt());
            launch.recycle();
        }
    }

    @Test public void appTransactionsOnThePorterWireStillRequireTheirCaller() {
        for (int code : new int[]{ServerConstants.BINDER_TRANSACTION_getDiagnostics, ServerConstants.BINDER_TRANSACTION_getApplications,
                ServerConstants.BINDER_TRANSACTION_setDebugLogging, eu.darken.porter.common.DiscoveredApplication.TRANSACTION,
                eu.darken.porter.common.GlobalAccess.TRANSACTION, eu.darken.porter.common.CompatibilitySetup.TRANSACTION,
                eu.darken.porter.common.UserServiceLaunch.TRANSACTION}) {
            Parcel request = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                request.writeInterfaceToken(PorterProtocol.DESCRIPTOR);
                request.setDataPosition(0);
                assertThrows(SecurityException.class, () -> service.getPorterEndpoint().onTransact(code, request, reply, 0));
            } finally { request.recycle(); reply.recycle(); }
        }
    }

    /**
     * The Porter counterpart of {@link #aGlobalPauseCannotOvertakeAnAttachReply}: the reply is the
     * delivery here, so the endpoint must hold the monitor until it has been built and handed back.
     */
    @Test public void aGlobalPauseCannotOvertakeAPorterAttachReply() throws Exception {
        var record = porterClient(mock(IPorterApplication.class));
        when(clients.findClient(CLIENT_UID, CLIENT_PID)).thenReturn(null);
        when(clients.attach(any(CallerIdentity.class), any(ClientCallback.class), eq(record.packageName), anyInt())).thenReturn(record);
        when(clients.attachedClients()).thenReturn(List.of());
        installed.applicationInfo = new android.content.pm.ApplicationInfo();
        installed.applicationInfo.uid = CLIENT_UID;
        Thread[] pause = new Thread[1];
        AtomicBoolean pauseStillBlocked = new AtomicBoolean();
        doAnswer(invocation -> {
            pause[0] = new Thread(() -> service.setGlobalAccess(false));
            pause[0].start();
            pause[0].join(500);
            pauseStillBlocked.set(pause[0].isAlive());
            return null;
        }).when(history).connected(any(), anyLong());
        Bundle args = new Bundle();
        args.putString(PorterProtocol.ATTACH_PACKAGE_NAME, record.packageName);
        service.getPorterEndpoint().attach(mock(IPorterApplication.class), args);
        assertTrue(pauseStillBlocked.get());
        pause[0].join(500);
    }

    @Test public void aPausedPorterClientIsToldAndRefusedUntilAccessResumes() throws Exception {
        var application = mock(IPorterApplication.class);
        when(application.asBinder()).thenReturn(new Binder());
        var record = porterClient(application);
        var paused = new AtomicBoolean(false);
        when(config.isAccessPaused()).thenAnswer(invocation -> paused.get());
        doAnswer(invocation -> { paused.set(invocation.getArgument(0)); return null; }).when(config).setAccessPaused(anyBoolean());

        service.setGlobalAccess(false);
        assertFalse(record.allowed);
        var state = org.mockito.ArgumentCaptor.forClass(Bundle.class);
        verify(application).dispatchPermissionStateChanged(state.capture());
        assertFalse(state.getValue().getBoolean(PorterProtocol.REPLY_PERMISSION_GRANTED));
        assertThrows(SecurityException.class, () -> service.getCore().enforceCallingPermission("transactRemote", new CallerIdentity(CLIENT_UID, CLIENT_PID)));

        service.setGlobalAccess(true);
        assertTrue(record.allowed);
        verify(application, times(2)).dispatchPermissionStateChanged(state.capture());
        assertTrue(state.getValue().getBoolean(PorterProtocol.REPLY_PERMISSION_GRANTED));
        service.getCore().enforceCallingPermission("transactRemote", new CallerIdentity(CLIENT_UID, CLIENT_PID));
    }
}
