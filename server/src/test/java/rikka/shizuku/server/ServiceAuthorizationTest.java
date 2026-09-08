package rikka.shizuku.server;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
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
import rikka.shizuku.server.util.Android17Compat;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import moe.shizuku.server.IShizukuApplication;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class ServiceAuthorizationTest {
    private static final int MANAGER_UID = 10100;
    private static final int CLIENT_UID = 10200;
    private static final int CLIENT_PID = 45678;
    private ShizukuService service;
    private ShizukuConfigManager config;
    private ShizukuClientManager clients;
    private ShizukuUserServiceManager userServices;
    private ClientRecord client;
    private PackageInfo installed;
    private MockedStatic<PackageManagerApis> packages;
    private MockedStatic<Android17Compat> permissions;
    private MockedStatic<Compatibility> companion;
    private MockedStatic<ActivityManagerApis> activities;

    @Before public void setup() throws Exception {
        // Skip the constructor's native rish startup; permission methods below remain real.
        service = mock(ShizukuService.class, CALLS_REAL_METHODS);
        config = mock(ShizukuConfigManager.class);
        clients = mock(ShizukuClientManager.class);
        userServices = mock(ShizukuUserServiceManager.class);
        field(ShizukuService.class, "managerAppId", MANAGER_UID);
        field(ShizukuService.class, "configManager", config);
        field(ShizukuService.class, "clientManager", clients);
        field(Service.class, "clientManager", clients);
        field(Service.class, "userServiceManager", userServices);
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

    private void field(Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(service, value);
    }

    @After public void close() {
        activities.close();
        companion.close();
        permissions.close();
        packages.close();
        ShadowBinder.reset();
    }

    private void reconcile() throws Exception {
        Method method = ShizukuService.class.getDeclaredMethod("reconcileRuntimePermission", int.class);
        method.setAccessible(true);
        method.invoke(service, CLIENT_UID);
    }

    @Test public void grantedClientStillCannotInvokeManagerOperations() {
        assertThrows(SecurityException.class, () -> service.getFlagsForUid(CLIENT_UID, ConfigManager.MASK_PERMISSION));
        assertThrows(SecurityException.class, () -> service.updateFlagsForUid(CLIENT_UID, ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_ALLOWED));
        assertThrows(SecurityException.class, () -> service.dispatchPermissionConfirmationResult(CLIENT_UID, CLIENT_PID, 1, new Bundle()));
        assertThrows(SecurityException.class, () -> service.attachUserService(null, new Bundle()));
        assertThrows(SecurityException.class, service::exit);
        verifyNoInteractions(config, userServices);
    }

    @Test public void diagnosticsAndApplicationsTransactionsRequireManager() {
        for (int code : new int[]{ServerConstants.BINDER_TRANSACTION_getDiagnostics, ServerConstants.BINDER_TRANSACTION_getApplications, eu.darken.porter.common.DiscoveredApplication.TRANSACTION, eu.darken.porter.common.GlobalAccess.TRANSACTION}) {
            Parcel request = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                request.writeInterfaceToken(ShizukuApiConstants.BINDER_DESCRIPTOR);
                request.setDataPosition(0);
                assertThrows(SecurityException.class, () -> service.onTransact(code, request, reply, 0));
            } finally { request.recycle(); reply.recycle(); }
        }
    }

    @Test public void managerUidCanReadFlagsButOtherAppsCannot() {
        ShadowBinder.setCallingUid(MANAGER_UID);
        assertEquals(ConfigManager.FLAG_ALLOWED, service.getFlagsForUid(CLIENT_UID, ConfigManager.MASK_PERMISSION));
        ShadowBinder.setCallingUid(CLIENT_UID);
        assertThrows(SecurityException.class, () -> service.getFlagsForUid(CLIENT_UID, ConfigManager.MASK_PERMISSION));
    }

    @Test public void permissionRevocationDropsGrantAndTerminatesUserServices() throws Exception {
        permissions.when(() -> Android17Compat.checkPermission(ServerConstants.PERMISSION, client.packageName, 0)).thenReturn(PackageManager.PERMISSION_DENIED);
        reconcile();
        assertFalse(client.allowed);
        verify(config).update(CLIENT_UID, null, ConfigManager.MASK_PERMISSION | ShizukuConfig.FLAG_PENDING_COMPANION, 0);
        verify(userServices).removeUserServicesForPackage(client.packageName);
        assertThrows(SecurityException.class, () -> service.enforceCallingPermission("client operation"));
    }

    @Test public void existingGrantSurvivesWhilePrimaryRuntimePermissionIsGranted() throws Exception {
        permissions.when(() -> Android17Compat.checkPermission(ServerConstants.PERMISSION, client.packageName, 0)).thenReturn(PackageManager.PERMISSION_GRANTED);
        reconcile();
        assertTrue(client.allowed);
        verify(config, never()).update(anyInt(), any(), anyInt(), anyInt());
        verifyNoInteractions(userServices);
        service.enforceCallingPermission("client operation");
    }

    @Test public void legacyGrantRequiresCompanionEvenWhenAndroidPermissionRemainsGranted() throws Exception {
        installed.requestedPermissions = new String[]{ServerConstants.LEGACY_PERMISSION};
        permissions.when(() -> Android17Compat.checkPermission(ServerConstants.LEGACY_PERMISSION, client.packageName, 0)).thenReturn(PackageManager.PERMISSION_GRANTED);
        reconcile();
        assertFalse(client.allowed);
        verify(userServices).removeUserServicesForPackage(client.packageName);
    }

    @Test public void trustedLegacyGrantSurvives() throws Exception {
        installed.requestedPermissions = new String[]{ServerConstants.LEGACY_PERMISSION};
        companion.when(Compatibility::isAvailable).thenReturn(true);
        permissions.when(() -> Android17Compat.checkPermission(ServerConstants.LEGACY_PERMISSION, client.packageName, 0)).thenReturn(PackageManager.PERMISSION_GRANTED);
        reconcile();
        assertTrue(client.allowed);
        verifyNoInteractions(userServices);
    }

    @Test public void legacyGrantCannotOverrideRevokedPorterPermissionForDualClient() throws Exception {
        installed.requestedPermissions = new String[]{ServerConstants.PERMISSION, ServerConstants.LEGACY_PERMISSION};
        companion.when(Compatibility::isAvailable).thenReturn(true);
        permissions.when(() -> Android17Compat.checkPermission(ServerConstants.PERMISSION, client.packageName, 0)).thenReturn(PackageManager.PERMISSION_DENIED);
        permissions.when(() -> Android17Compat.checkPermission(ServerConstants.LEGACY_PERMISSION, client.packageName, 0)).thenReturn(PackageManager.PERMISSION_GRANTED);
        reconcile();
        assertFalse(client.allowed);
        verify(userServices).removeUserServicesForPackage(client.packageName);
    }

    @Test public void terminalClientWithoutRuntimePermissionKeepsExplicitConsent() throws Exception {
        installed.requestedPermissions = null;
        reconcile();
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
        assertNull(ShizukuService.providerSuffix(installed));
    }
    @Test public void acceptedNewConnectionIsRecordedWithoutGrantingAccess() throws Exception {
        var history = mock(ConnectionHistory.class);
        field(ShizukuService.class, "connectionHistory", history);
        field(ShizukuService.class, "historyWriter", (java.util.concurrent.Executor) Runnable::run);
        when(clients.findClient(CLIENT_UID, CLIENT_PID)).thenReturn(null);
        when(clients.addClient(eq(CLIENT_UID), eq(CLIENT_PID), any(), eq(client.packageName), anyInt())).thenReturn(client);
        installed.applicationInfo = new android.content.pm.ApplicationInfo();
        installed.applicationInfo.uid = CLIENT_UID;
        installed.firstInstallTime = 100;
        Bundle args = new Bundle();
        args.putString(ShizukuApiConstants.ATTACH_APPLICATION_PACKAGE_NAME, client.packageName);
        var callback = mock(IShizukuApplication.class);
        service.attachApplication(callback, args);
        verify(callback).bindApplication(any());
        verify(history).connected(eq(installed), anyLong());
        verify(config, never()).update(anyInt(), any(), anyInt(), anyInt());
    }

    @Test public void failedConnectionReplyDoesNotCreateHistory() throws Exception {
        var history = mock(ConnectionHistory.class);
        field(ShizukuService.class, "connectionHistory", history);
        field(ShizukuService.class, "historyWriter", (java.util.concurrent.Executor) Runnable::run);
        when(clients.findClient(CLIENT_UID, CLIENT_PID)).thenReturn(null);
        when(clients.addClient(eq(CLIENT_UID), eq(CLIENT_PID), any(), eq(client.packageName), anyInt())).thenReturn(client);
        Bundle args = new Bundle();
        args.putString(ShizukuApiConstants.ATTACH_APPLICATION_PACKAGE_NAME, client.packageName);
        var callback = mock(IShizukuApplication.class);
        doThrow(new android.os.RemoteException()).when(callback).bindApplication(any());
        service.attachApplication(callback, args);
        verifyNoInteractions(history);
    }

    @Test public void discoveryKeepsOtherAppsAfterOversizedEntryAndPreservesFailedProfileHistory() throws Exception {
        var history = mock(ConnectionHistory.class);
        field(ShizukuService.class, "connectionHistory", history);
        ShadowBinder.setCallingUid(MANAGER_UID);
        var nativeApp = ApplicationDiscoveryTest.app("native", CLIENT_UID, ServerConstants.PERMISSION);
        var oversized = ApplicationDiscoveryTest.app("x".repeat(30000), CLIENT_UID + 1, ServerConstants.PERMISSION);
        var legacy = ApplicationDiscoveryTest.app("legacy", CLIENT_UID + 2, ServerConstants.LEGACY_PERMISSION);
        var ownerPackages = List.of(nativeApp, oversized, legacy);
        try (var users = mockStatic(rikka.hidden.compat.UserManagerApis.class);
             var installedApps = mockStatic(rikka.shizuku.server.util.InstalledPackagesCompat.class)) {
            users.when(rikka.hidden.compat.UserManagerApis::getUserIdsNoThrow).thenReturn(List.of(0, 10));
            installedApps.when(() -> rikka.shizuku.server.util.InstalledPackagesCompat.getInstalledPackages(anyLong(), eq(0))).thenReturn(ownerPackages);
            installedApps.when(() -> rikka.shizuku.server.util.InstalledPackagesCompat.getInstalledPackages(anyLong(), eq(10))).thenThrow(new SecurityException("profile unavailable"));
            Parcel request = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                request.writeInterfaceToken(ShizukuApiConstants.BINDER_DESCRIPTOR);
                request.writeInt(-1);
                request.setDataPosition(0);
                assertTrue(service.onTransact(eu.darken.porter.common.DiscoveredApplication.TRANSACTION, request, reply, 0));
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
        assertThrows(SecurityException.class, () -> service.enforceCallingPermission("client operation"));
    }

    @Test public void pendingAccessActivatesOnlyAfterTrustedCompanionAndVerifiedRuntimeGrant() throws Exception {
        var entry = pendingLegacy();
        assertFalse(entry.isAllowed());
        assertFalse(entry.isDenied());
        reconcile();
        assertFalse(client.allowed);
        companion.when(Compatibility::isAvailable).thenReturn(true);
        permissions.when(() -> Android17Compat.checkPermission(ServerConstants.LEGACY_PERMISSION, client.packageName, 0)).thenReturn(PackageManager.PERMISSION_GRANTED);
        reconcile();
        permissions.verify(() -> Android17Compat.grantRuntimePermission(client.packageName, ServerConstants.LEGACY_PERMISSION, 0));
        verify(config).update(CLIENT_UID, null, ConfigManager.MASK_PERMISSION | ShizukuConfig.FLAG_PENDING_COMPANION, ConfigManager.FLAG_ALLOWED);
        assertTrue(client.allowed);
    }

    @Test public void failedRuntimeGrantDoesNotActivatePendingAccess() throws Exception {
        pendingLegacy();
        companion.when(Compatibility::isAvailable).thenReturn(true);
        permissions.when(() -> Android17Compat.checkPermission(ServerConstants.LEGACY_PERMISSION, client.packageName, 0)).thenReturn(PackageManager.PERMISSION_DENIED);
        reconcile();
        assertFalse(client.allowed);
        verify(config, never()).update(anyInt(), any(), anyInt(), eq(ConfigManager.FLAG_ALLOWED));
    }

    @Test public void removingCompanionSuspendsAccessButKeepsIntent() throws Exception {
        installed.requestedPermissions = new String[]{ServerConstants.LEGACY_PERMISSION};
        reconcile();
        verify(config).update(CLIENT_UID, List.of(client.packageName),
                ConfigManager.MASK_PERMISSION | ShizukuConfig.FLAG_PENDING_COMPANION, ShizukuConfig.FLAG_PENDING_COMPANION);
        verify(userServices).removeUserServicesForPackage(client.packageName);
        assertFalse(client.allowed);
    }

    @Test public void androidRevocationWithCompanionPresentDoesNotBecomePending() throws Exception {
        installed.requestedPermissions = new String[]{ServerConstants.LEGACY_PERMISSION};
        companion.when(Compatibility::isAvailable).thenReturn(true);
        permissions.when(() -> Android17Compat.checkPermission(ServerConstants.LEGACY_PERMISSION, client.packageName, 0)).thenReturn(PackageManager.PERMISSION_DENIED);
        reconcile();
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
        reconcile();
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
        service.dispatchPermissionConfirmationResult(CLIENT_UID, CLIENT_PID, 42, result);
        verify(record).dispatchRequestPermissionResult(42, false);
        verify(config, never()).update(anyInt(), any(), anyInt(), anyInt());
        assertFalse(record.allowed);
    }

    private void globalAccess(boolean enabled) throws Exception {
        Method method = ShizukuService.class.getDeclaredMethod("setGlobalAccess", boolean.class);
        method.setAccessible(true);
        method.invoke(service, enabled);
    }

    @Test public void globalPauseBlocksEvenStaleAllowedRecordsAndRuntimePermissionFallback() {
        when(config.isAccessPaused()).thenReturn(true);
        assertThrows(SecurityException.class, () -> service.enforceCallingPermission("transactRemote"));
        when(clients.findClient(CLIENT_UID, CLIENT_PID)).thenReturn(null);
        activities.when(() -> ActivityManagerApis.checkPermission(ServerConstants.PERMISSION, CLIENT_PID, CLIENT_UID)).thenReturn(PackageManager.PERMISSION_GRANTED);
        assertThrows(SecurityException.class, () -> service.enforceCallingPermission("newProcess"));
        ShadowBinder.setCallingUid(MANAGER_UID);
        service.enforceCallingPermission("manager operation");
    }

    @Test public void pauseAndResumeKeepSavedDecisionsAndUpdateClients() throws Exception {
        var paused = new java.util.concurrent.atomic.AtomicBoolean(false);
        when(config.isAccessPaused()).thenAnswer(inv -> paused.get());
        doAnswer(inv -> { paused.set(inv.getArgument(0)); return null; }).when(config).setAccessPaused(anyBoolean());
        when(clients.attachedClients()).thenReturn(List.of(client));
        var entry = config.find(CLIENT_UID);
        int before = entry.flags;
        globalAccess(false);
        assertFalse(client.allowed);
        verify(userServices).setAccessPaused(true);
        assertEquals(before, entry.flags);
        verify(config, never()).update(anyInt(), any(), anyInt(), anyInt());
        globalAccess(true);
        assertTrue(client.allowed);
        assertEquals(before, entry.flags);
        verify(userServices).setAccessPaused(false);
        verify(client.client, times(2)).bindApplication(any(Bundle.class));
    }

    @Test public void permissionRequestWhilePausedReturnsDenialWithoutChangingConfig() {
        when(config.isAccessPaused()).thenReturn(true);
        var record = spy(client);
        service.showPermissionConfirmation(17, record, CLIENT_UID, CLIENT_PID, 0);
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
        reconcile();
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
        var record = realClients.addClient(CLIENT_UID, CLIENT_PID, callback, client.packageName, 13);
        assertNotNull(record);
        assertFalse(record.allowed);
        assertEquals(List.of(record), realClients.attachedClients());
    }
}
