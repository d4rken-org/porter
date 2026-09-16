package eu.darken.porter.privileged;

import static android.Manifest.permission.WRITE_SECURE_SETTINGS;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_PERMISSION_GRANTED;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SERVER_PATCH_VERSION;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SERVER_SECONTEXT;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SERVER_UID;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SERVER_VERSION;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE;
import static eu.darken.porter.privileged.ServerConstants.PERMISSION;

import eu.darken.porter.common.DiscoveredApplication;

import android.content.Context;
import android.content.IContentProvider;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.ddm.DdmHandleAppName;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Stream;

import kotlin.collections.ArraysKt;
import moe.shizuku.api.BinderContainer;
import eu.darken.porter.common.util.BuildUtils;
import eu.darken.porter.common.util.OsUtils;
import moe.shizuku.starter.util.IContentProviderCompat;
import rikka.hidden.compat.ActivityManagerApis;
import rikka.hidden.compat.DeviceIdleControllerApis;
import rikka.hidden.compat.PackageManagerApis;
import eu.darken.porter.privileged.util.Android17Compat;
import rikka.hidden.compat.UserManagerApis;
import rikka.parcelablelist.ParcelableListSlice;
import eu.darken.porter.core.CallerIdentity;
import eu.darken.porter.core.ManagerOperations;
import eu.darken.porter.core.PorterCore;
import eu.darken.porter.core.ServerPolicy;
import eu.darken.porter.porsh.PorshConfig;
import eu.darken.porter.protocol.PorterProtocol;
import rikka.shizuku.ShizukuApiConstants;
import rikka.shizuku.server.util.HandlerUtil;
import rikka.shizuku.server.util.Logger;
import eu.darken.porter.privileged.util.InstalledPackagesCompat;
import eu.darken.porter.privileged.util.PackageIdentity;
import rikka.shizuku.server.util.UserHandleCompat;
import rikka.shizuku.server.ClientRecord;
import rikka.shizuku.server.ConfigManager;
import rikka.shizuku.server.UserServiceManager;

public class PorterServer implements ServerPolicy, ManagerOperations {

    public static final String MANAGER_APPLICATION_ID = moe.shizuku.server.BuildConfig.MANAGER_APPLICATION_ID;

    private static final Logger LOGGER = new Logger("Service");

    public static void main(String[] args) {
        DdmHandleAppName.setAppName("porter_server", 0);
        PorshConfig.setLibraryPath(System.getProperty("shizuku.library.path"));
        PorshConfig.init(ShizukuApiConstants.BINDER_DESCRIPTOR, 30000);

        Looper.prepareMainLooper();
        bootstrap(ShizukuServiceEndpoint::new, PorterServiceEndpoint::new);
        Looper.loop();
    }

    private static void waitSystemService(String name) {
        while (ServiceManager.getService(name) == null) {
            try {
                LOGGER.i("service " + name + " is not started, wait 1s.");
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                LOGGER.w(e.getMessage(), e);
            }
        }
    }

    public static ApplicationInfo getManagerApplicationInfo() {
        return Android17Compat.getApplicationInfo(MANAGER_APPLICATION_ID, 0, 0);
    }

    /**
     * The startup verdict on the manager lookup; {@code 0} means carry on. A failed lookup exits for
     * the same reason an absent manager does: the alternative is publishing access while holding no
     * verified baseline, and the manager restarts the server anyway.
     */
    static int managerStartupExitCode(PackageIdentity.Result result) {
        return result.state == PackageIdentity.State.PRESENT ? 0 : ServerConstants.MANAGER_APP_NOT_FOUND;
    }

    public static PorterServer bootstrap(Function<PorterServer, ShizukuServiceEndpoint> endpointFactory,
                                           Function<PorterServer, PorterServiceEndpoint> porterEndpointFactory) {
        LOGGER.i("starting server...");

        waitSystemService("package");
        waitSystemService(Context.ACTIVITY_SERVICE);
        waitSystemService(Context.USER_SERVICE);
        waitSystemService(Context.APP_OPS_SERVICE);

        // One signed observation, so the app id that authorises manager calls and the identity the
        // reconciler compares against can never describe two different installations. A failed
        // lookup exits too: publishing access while holding no verified baseline is worse than a
        // restart, and the manager restarts the server anyway.
        PackageIdentity.Result manager = PackageIdentity.of(MANAGER_APPLICATION_ID, 0);
        if (managerStartupExitCode(manager) != 0) {
            LOGGER.w("manager app is %s in user 0, exiting...", manager.state);
            System.exit(managerStartupExitCode(manager));
        }

        assert manager.observed != null;

        ShizukuConfigManager configManager = new ShizukuConfigManager();
        ShizukuClientManager clientManager = new ShizukuClientManager(configManager);
        ShizukuUserServiceManager userServiceManager = new ShizukuUserServiceManager();

        PorterServer service = new PorterServer(
                userServiceManager,
                clientManager,
                configManager,
                manager.observed.appId,
                new ConnectionHistory(new File("/data/user_de/0/com.android.shell/porter-connections.json")),
                Executors.newSingleThreadExecutor(),
                endpointFactory,
                porterEndpointFactory);

        HandlerUtil.setMainHandler(service.mainHandler);

        // A debug build logs its debug detail unconditionally, the way it always has. The gate
        // exists to keep that detail out of release builds except while a recording wants it.
        Logger.setDebugAlways(moe.shizuku.server.BuildConfig.DEBUG);

        userServiceManager.setAccessPaused(configManager.isAccessPaused());

        ApkReconciler reconciler = new ApkReconciler(
                MANAGER_APPLICATION_ID,
                new PackageIdentity.Identity(MANAGER_APPLICATION_ID, manager.observed.appId, manager.observed.signerDigests),
                userServiceManager,
                new ApkReconciler.SystemPackageOracle(),
                new ApkReconciler.ExecutorScheduler(),
                System::exit);
        service.reconciler = reconciler;
        userServiceManager.setReconciler(reconciler);

        BinderSender.register(service.endpoint, service.porterEndpoint);
        try {
            PermissionObserver.register(uid -> service.mainHandler.post(() -> service.reconcileRuntimePermission(uid)));
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.w(e, "Permission observer unavailable; reconciling at startup and client attach");
        }

        service.mainHandler.post(() -> {
            for (int uid : configManager.allowedUids()) {
                service.reconcileRuntimePermission(uid);
            }
            service.sendBinderToManager();
            service.sendBinderToClient();
        });

        reconciler.start();

        return service;
    }

    @SuppressWarnings({"FieldCanBeLocal"})
    private final Handler mainHandler;
    //private final Context systemContext = HiddenApiBridge.getSystemContext();
    private final ShizukuUserServiceManager userServiceManager;
    private final ShizukuClientManager clientManager;
    private final ShizukuConfigManager configManager;
    private final PorterCore<ShizukuUserServiceManager, ShizukuClientManager, ShizukuConfigManager> core;
    private final ShizukuServiceEndpoint endpoint;
    private final PorterServiceEndpoint porterEndpoint;
    private final int managerAppId;
    ApkReconciler reconciler;
    private final java.util.concurrent.Executor historyWriter;
    final DebugLogLeases debugLogLeases = new DebugLogLeases();
    private final ConnectionHistory connectionHistory;

    public PorterServer(ShizukuUserServiceManager userServiceManager,
                          ShizukuClientManager clientManager,
                          ShizukuConfigManager configManager,
                          int managerAppId,
                          ConnectionHistory connectionHistory,
                          java.util.concurrent.Executor historyWriter,
                          Function<PorterServer, ShizukuServiceEndpoint> endpointFactory,
                          Function<PorterServer, PorterServiceEndpoint> porterEndpointFactory) {
        this.userServiceManager = userServiceManager;
        this.clientManager = clientManager;
        this.configManager = configManager;
        this.managerAppId = managerAppId;
        this.connectionHistory = connectionHistory;
        this.historyWriter = historyWriter;
        this.core = new PorterCore<>(userServiceManager, clientManager, configManager, this);
        this.endpoint = endpointFactory.apply(this);
        this.porterEndpoint = porterEndpointFactory.apply(this);
        this.mainHandler = new Handler(Looper.myLooper());
    }

    public PorterCore<ShizukuUserServiceManager, ShizukuClientManager, ShizukuConfigManager> getCore() {
        return core;
    }

    public ShizukuServiceEndpoint getEndpoint() {
        return endpoint;
    }

    public PorterServiceEndpoint getPorterEndpoint() {
        return porterEndpoint;
    }

    public ShizukuUserServiceManager getUserServiceManager() {
        return userServiceManager;
    }

    public ShizukuClientManager getClientManager() {
        return clientManager;
    }

    public ShizukuConfigManager getConfigManager() {
        return configManager;
    }

    @Override
    public boolean checkCallerManagerPermission(@NonNull String func, @NonNull CallerIdentity caller) {
        return caller.appId() == managerAppId;
    }

    private int checkCallingPermission(CallerIdentity caller) {
        try {
            if (ActivityManagerApis.checkPermission(PERMISSION, caller.pid, caller.uid) == PackageManager.PERMISSION_GRANTED) {
                return PackageManager.PERMISSION_GRANTED;
            }
            if (Compatibility.isAvailable()) {
                return ActivityManagerApis.checkPermission(ServerConstants.LEGACY_PERMISSION, caller.pid, caller.uid);
            }
        } catch (Throwable tr) {
            LOGGER.w(tr, "checkCallingPermission");
        }
        return PackageManager.PERMISSION_DENIED;
    }

    static ClientRouting.Wire route(PackageInfo client) {
        if (client == null || client.requestedPermissions == null) return null;
        if (client.permissions != null) {
            for (android.content.pm.PermissionInfo permission : client.permissions) {
                if (permission.name != null && permission.name.endsWith(".permission.MANAGER")) return null;
            }
        }
        if (!ClientRouting.requests(client.requestedPermissions, PERMISSION)
                && !ClientRouting.requests(client.requestedPermissions, ServerConstants.LEGACY_PERMISSION)) return null;
        return ClientRouting.route(client.requestedPermissions, Compatibility.isAvailable());
    }

    @Override
    public boolean checkCallerPermission(@NonNull String func, @NonNull CallerIdentity caller, @Nullable ClientRecord clientRecord) {
        if (caller.appId() == managerAppId) {
            return true;
        }
        if (configManager.isAccessPaused()) throw new SecurityException("App access is paused");
        if (clientRecord == null && checkCallingPermission(caller) == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        return false;
    }

    @Override
    public void exit() {
        LOGGER.i("exit");
        System.exit(0);
    }

    @Override
    public void attachUserService(IBinder binder, String token) {
        if (configManager.isAccessPaused()) throw new SecurityException("App access is paused");
        // Above the monitor: reading the descriptor is a synchronous round trip to the service being
        // attached, and Binder has no client-side timeout.
        String interfaceDescriptor = UserServiceManager.getInterfaceDescriptor(binder);
        synchronized (clientManager) {
            core.attachUserService(binder, token, interfaceDescriptor);
        }
    }

    @Override
    public void onAttaching(@NonNull CallerIdentity caller, @NonNull String packageName) {
        if (!MANAGER_APPLICATION_ID.equals(packageName)) {
            // Declaring a client permission only decides who gets the binder pushed (see providerSuffix).
            // Terminal clients (porsh) fetch it themselves and declare nothing; they are admitted here on
            // the uid/package check above and gated by the user's explicit decision like any client.
            reconcileRuntimePermission(caller.uid);
        }
    }

    @Override
    public void onAttached(@NonNull ClientRecord clientRecord, boolean created, @NonNull Bundle reply) {
        LOGGER.d("attachApplication: %s %d %d", clientRecord.packageName, clientRecord.uid, clientRecord.pid);

        if (!MANAGER_APPLICATION_ID.equals(clientRecord.packageName)) {
            return;
        }
        // Both wires reach this hook; the manager is not a permission-gated client on either.
        reply.remove(BIND_APPLICATION_PERMISSION_GRANTED);
        reply.remove(BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE);
        reply.remove(PorterProtocol.REPLY_PERMISSION_GRANTED);
        reply.remove(PorterProtocol.REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE);
        try {
            Android17Compat.grantRuntimePermission(MANAGER_APPLICATION_ID,
                    WRITE_SECURE_SETTINGS, UserHandleCompat.getUserId(clientRecord.uid));
        } catch (RemoteException e) {
            LOGGER.w(e, "grant WRITE_SECURE_SETTINGS");
        }
    }

    @Override
    public void onBound(@NonNull ClientRecord clientRecord, boolean created) {
        if (MANAGER_APPLICATION_ID.equals(clientRecord.packageName) || !created) {
            return;
        }
        int callingUid = clientRecord.uid;
        String requestPackageName = clientRecord.packageName;
        long connectedAt = System.currentTimeMillis();
        historyWriter.execute(() -> {
            try {
                PackageInfo info = Android17Compat.getPackageInfo(requestPackageName, 0, callingUid / 100000);
                if (info != null && info.applicationInfo != null && info.applicationInfo.uid == callingUid) {
                    connectionHistory.connected(info, connectedAt);
                }
            } catch (RuntimeException e) {
                LOGGER.w(e, "Cannot record client connection");
            }
        });
    }

    @Override
    public void showPermissionConfirmation(int requestCode, @NonNull ClientRecord clientRecord, @NonNull CallerIdentity caller, int userId) {
        if (configManager.isAccessPaused()) {
            clientRecord.dispatchRequestPermissionResult(requestCode, false);
            return;
        }
        Boolean legacyOnly = uidUsesLegacyOnly(caller.uid);
        if (legacyOnly == null || (legacyOnly && !Compatibility.isAvailable())) {
            clientRecord.dispatchRequestPermissionResult(requestCode, false);
            return;
        }
        ApplicationInfo ai = Android17Compat.getApplicationInfo(clientRecord.packageName, 0, userId);
        if (ai == null) {
            return;
        }

        PackageInfo pi = Android17Compat.getPackageInfo(MANAGER_APPLICATION_ID, 0, userId);
        UserInfo userInfo = UserManagerApis.getUserInfo(userId);
        boolean isWorkProfileUser = BuildUtils.atLeast30() ?
                "android.os.usertype.profile.MANAGED".equals(userInfo.userType) :
                (userInfo.flags & UserInfo.FLAG_MANAGED_PROFILE) != 0;
        if (pi == null && !isWorkProfileUser) {
            LOGGER.w("Manager not found in non work profile user %d. Revoke permission", userId);
            clientRecord.dispatchRequestPermissionResult(requestCode, false);
            return;
        }

        Intent intent = new Intent(ServerConstants.REQUEST_PERMISSION_ACTION)
                .setPackage(MANAGER_APPLICATION_ID)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
                .putExtra("uid", caller.uid)
                .putExtra("pid", caller.pid)
                .putExtra("requestCode", requestCode)
                .putExtra("applicationInfo", ai);
        ActivityManagerApis.startActivityNoThrow(intent, null, isWorkProfileUser ? 0 : userId);
    }

    @Override
    public void dispatchPermissionConfirmationResult(int requestUid, int requestPid, int requestCode, boolean allowed, boolean onetime) {
        synchronized (clientManager) {
            Boolean legacyOnly = uidUsesLegacyOnly(requestUid);
            if (allowed && legacyOnly == null) {
                allowed = false;
                onetime = true;
            }
            boolean pending = allowed && Boolean.TRUE.equals(legacyOnly) && !Compatibility.isAvailable();
            if (pending) allowed = false;

            LOGGER.i("dispatchPermissionConfirmationResult: uid=%d, pid=%d, requestCode=%d, allowed=%s, onetime=%s",
                    requestUid, requestPid, requestCode, Boolean.toString(allowed), Boolean.toString(onetime));

            List<ClientRecord> records = clientManager.findClients(requestUid);
            if (records.isEmpty()) {
                LOGGER.w("dispatchPermissionConfirmationResult: no client for uid %d was found", requestUid);
            } else {
                for (ClientRecord record : records) {
                    record.allowed = allowed && !configManager.isAccessPaused();
                    if (record.pid == requestPid) {
                        record.dispatchRequestPermissionResult(requestCode, record.allowed);
                    }
                }
            }

            if (!onetime) {
                configManager.update(requestUid, PackageManagerApis.getPackagesForUidNoThrow(requestUid),
                        ConfigManager.MASK_PERMISSION | ShizukuConfig.FLAG_PENDING_COMPANION,
                        pending ? ShizukuConfig.FLAG_PENDING_COMPANION : allowed ? ConfigManager.FLAG_ALLOWED : ConfigManager.FLAG_DENIED);
            }

            if (!onetime && !pending) {
                setRuntimePermissionsForUid(requestUid, allowed);
            }
        }
    }

    private void setRuntimePermissionsForUid(int uid, boolean allowed) {
        int userId = UserHandleCompat.getUserId(uid);
        boolean legacy = Compatibility.isAvailable();
        for (String packageName : PackageManagerApis.getPackagesForUidNoThrow(uid)) {
            PackageInfo pi = Android17Compat.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS, userId);
            if (pi == null) continue;
            for (String permission : new String[]{PERMISSION, ServerConstants.LEGACY_PERMISSION}) {
                if (ServerConstants.LEGACY_PERMISSION.equals(permission) && !legacy) continue;
                if (!ClientRouting.requests(pi.requestedPermissions, permission)) continue;
                try {
                    if (allowed) Android17Compat.grantRuntimePermission(packageName, permission, userId);
                    else Android17Compat.revokeRuntimePermission(packageName, permission, userId);
                } catch (Throwable e) {
                    LOGGER.w(e, "Unable to synchronize %s for %s", permission, packageName);
                }
            }
        }
    }

    @Nullable
    private Boolean uidUsesLegacyOnly(int uid) {
        boolean legacy = false;
        boolean unresolved = false;
        List<String> packages = PackageManagerApis.getPackagesForUidNoThrow(uid);
        if (packages.isEmpty()) return null;
        for (String name : packages) {
            PackageInfo pi = Android17Compat.getPackageInfo(name, PackageManager.GET_PERMISSIONS, UserHandleCompat.getUserId(uid));
            if (pi == null) { unresolved = true; continue; }
            if (ClientRouting.requests(pi.requestedPermissions, PERMISSION)) return false;
            legacy |= ClientRouting.requests(pi.requestedPermissions, ServerConstants.LEGACY_PERMISSION);
        }
        return unresolved ? null : legacy;
    }

    private void suspendUid(int uid) {
        for (ClientRecord record : clientManager.findClients(uid)) record.allowed = false;
        for (String name : PackageManagerApis.getPackagesForUidNoThrow(uid)) onPermissionRevoked(name);
    }

    void reconcileRuntimePermission(int uid) {
        synchronized (clientManager) {
            ShizukuConfig.PackageEntry entry = configManager.find(uid);
            if (entry == null || (!entry.isAllowed() && !entry.isPendingCompanion())) return;
            int userId = UserHandleCompat.getUserId(uid);
            Boolean legacyOnly = uidUsesLegacyOnly(uid);
            if (legacyOnly == null) return;
            boolean legacy = Compatibility.isAvailable();
            int mask = ConfigManager.MASK_PERMISSION | ShizukuConfig.FLAG_PENDING_COMPANION;
            if (legacyOnly && !legacy) {
                configManager.update(uid, PackageManagerApis.getPackagesForUidNoThrow(uid), mask, ShizukuConfig.FLAG_PENDING_COMPANION);
                suspendUid(uid);
                return;
            }
            boolean pending = entry.isPendingCompanion();
            if (pending) {
                if (!legacyOnly) {
                    configManager.update(uid, null, mask, 0);
                    suspendUid(uid);
                    return;
                }
                setRuntimePermissionsForUid(uid, true);
            }
            for (String name : PackageManagerApis.getPackagesForUidNoThrow(uid)) {
                PackageInfo pi = Android17Compat.getPackageInfo(name, PackageManager.GET_PERMISSIONS, userId);
                if (pi == null) {
                    if (pending) { suspendUid(uid); return; }
                    continue;
                }
                String permission;
                if (ClientRouting.requests(pi.requestedPermissions, PERMISSION)) permission = PERMISSION;
                else if (legacyOnly && ClientRouting.requests(pi.requestedPermissions, ServerConstants.LEGACY_PERMISSION)) permission = ServerConstants.LEGACY_PERMISSION;
                else continue;
                if (Android17Compat.checkPermission(permission, name, userId) == PackageManager.PERMISSION_GRANTED) continue;
                // A pending grant may still be blocked by Android policy. Active grants respect revocation.
                if (!pending) configManager.update(uid, null, mask, 0);
                suspendUid(uid);
                return;
            }
            if (pending) {
                configManager.update(uid, null, mask, ConfigManager.FLAG_ALLOWED);
                for (ClientRecord record : clientManager.findClients(uid)) record.allowed = !configManager.isAccessPaused();
            }
        }
    }

    void setGlobalAccess(boolean enabled) {
        synchronized (clientManager) {
            boolean paused = !enabled;
            if (configManager.isAccessPaused() == paused) return;
            configManager.setAccessPaused(paused);
            userServiceManager.setAccessPaused(paused);
            if (!paused) {
                for (int uid : configManager.allowedUids()) reconcileRuntimePermission(uid);
            }
            for (ClientRecord record : clientManager.attachedClients()) {
                if (UserHandleCompat.getAppId(record.uid) == managerAppId) continue;
                ShizukuConfig.PackageEntry entry = configManager.find(record.uid);
                record.allowed = !paused && entry != null && entry.isAllowed();
                Bundle reply = new Bundle();
                reply.putInt(BIND_APPLICATION_SERVER_UID, OsUtils.getUid());
                reply.putInt(BIND_APPLICATION_SERVER_VERSION, record.apiVersion == -1 ? 12 : ShizukuApiConstants.SERVER_VERSION);
                reply.putInt(BIND_APPLICATION_SERVER_PATCH_VERSION, ShizukuApiConstants.SERVER_PATCH_VERSION);
                reply.putString(BIND_APPLICATION_SERVER_SECONTEXT, OsUtils.getSELinuxContext());
                reply.putBoolean(BIND_APPLICATION_PERMISSION_GRANTED, record.allowed);
                reply.putBoolean(BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, entry != null && entry.isDenied());
                try {
                    if (record.client != null) {
                        record.client.bindApplication(reply);
                    } else {
                        record.callback.onPermissionStateChanged(record.allowed, entry != null && entry.isDenied());
                    }
                } catch (Throwable e) { LOGGER.w(e, "Cannot notify client of global access change"); }
            }
        }
    }

    private int getFlagsForUidInternal(int uid, int mask) {
        ShizukuConfig.PackageEntry entry = configManager.find(uid);
        return entry == null ? 0 : entry.flags & mask;
    }

    @Override
    public int getFlagsForUid(int uid, int mask) {
        return getFlagsForUidInternal(uid, mask);
    }

    @Override
    public void updateFlagsForUid(int uid, int mask, int value) {
        synchronized (clientManager) {
            int userId = UserHandleCompat.getUserId(uid);

            if ((mask & ConfigManager.MASK_PERMISSION) != 0) {
                mask |= ShizukuConfig.FLAG_PENDING_COMPANION;
                value &= ~ShizukuConfig.FLAG_PENDING_COMPANION;
                Boolean legacyOnly = uidUsesLegacyOnly(uid);
                if ((value & ConfigManager.FLAG_ALLOWED) != 0 && legacyOnly == null) {
                    throw new IllegalStateException("Cannot read application permissions. Try again.");
                }
                if ((value & ConfigManager.FLAG_ALLOWED) != 0 && Boolean.TRUE.equals(legacyOnly) && !Compatibility.isAvailable()) {
                    value = (value & ~ConfigManager.MASK_PERMISSION) | ShizukuConfig.FLAG_PENDING_COMPANION;
                    configManager.update(uid, PackageManagerApis.getPackagesForUidNoThrow(uid), mask, value);
                    suspendUid(uid);
                    return;
                }
                boolean allowed = (value & ConfigManager.FLAG_ALLOWED) != 0;
                boolean denied = (value & ConfigManager.FLAG_DENIED) != 0;

                List<ClientRecord> records = clientManager.findClients(uid);
                for (ClientRecord record : records) {
                    if (allowed) {
                        record.allowed = !configManager.isAccessPaused();
                    } else {
                        record.allowed = false;
                        ActivityManagerApis.forceStopPackageNoThrow(record.packageName, UserHandleCompat.getUserId(record.uid));
                    }
                }
                if (!allowed) {
                    // Daemon user services outlive the client process, so tear down by package, not by attached record.
                    for (String packageName : PackageManagerApis.getPackagesForUidNoThrow(uid)) {
                        onPermissionRevoked(packageName);
                    }
                }

                setRuntimePermissionsForUid(uid, allowed);
            }

            configManager.update(uid, PackageManagerApis.getPackagesForUidNoThrow(uid), mask, value);
        }
    }

    private void onPermissionRevoked(String packageName) {
        userServiceManager.removeUserServicesForPackage(packageName);
    }

    ParcelableListSlice<PackageInfo> getApplications(int userId) {
        List<PackageInfo> list = new ArrayList<>();
        List<Integer> users = new ArrayList<>();
        if (userId == -1) {
            users.addAll(UserManagerApis.getUserIdsNoThrow());
        } else {
            users.add(userId);
        }

        for (int user : users) {
            for (PackageInfo pi : InstalledPackagesCompat.getInstalledPackagesNoThrow(PackageManager.GET_META_DATA | PackageManager.GET_PERMISSIONS, user)) {
                if (Objects.equals(MANAGER_APPLICATION_ID, pi.packageName)
                        || Objects.equals(ServerConstants.COMPAT_APPLICATION_ID, pi.packageName)) continue;
                if (pi.applicationInfo == null) continue;

                int uid = pi.applicationInfo.uid;
                int flags = 0;
                ShizukuConfig.PackageEntry entry = configManager.find(uid);
                if (entry != null) {
                    if (entry.packages != null && !entry.packages.contains(pi.packageName))
                        continue;
                    flags = entry.flags & ConfigManager.MASK_PERMISSION;
                }

                // Anything the user decided on is manageable here, declared permission or not (porsh
                // terminals). Undecided packages are only suggested when Porter would push to them.
                if (flags != 0) {
                    list.add(pi);
                } else if (route(pi) != null) {
                    list.add(pi);
                }
            }

        }
        return new ParcelableListSlice<>(list);
    }

    void writeDiscovery(int userId, Parcel reply) {
        java.util.Collection<Integer> users = userId == -1 ? UserManagerApis.getUserIdsNoThrow() : java.util.Collections.singletonList(userId);
        if (users.isEmpty()) throw new IllegalStateException("Cannot enumerate Android users");
        List<DiscoveredApplication> apps = new ArrayList<>();
        List<Integer> failedUsers = new ArrayList<>();
        boolean companion = Compatibility.isAvailable();
        for (int uid : configManager.allowedUids()) reconcileRuntimePermission(uid);
        for (int user : users) {
            try {
                long snapshotStartedAt = System.currentTimeMillis();
                List<PackageInfo> installed = InstalledPackagesCompat.getInstalledPackages(
                        PackageManager.GET_META_DATA | PackageManager.GET_PERMISSIONS, user);
                if (installed == null || installed.isEmpty()) throw new IllegalStateException("Empty package enumeration");
                connectionHistory.pruneUser(user, installed, snapshotStartedAt);
                for (PackageInfo info : installed) {
                    if (info.applicationInfo == null || MANAGER_APPLICATION_ID.equals(info.packageName)
                            || ServerConstants.COMPAT_APPLICATION_ID.equals(info.packageName)) continue;
                    ShizukuConfig.PackageEntry decision = configManager.find(info.applicationInfo.uid);
                    long lastConnected = connectionHistory.get(info);
                    boolean declared = ClientRouting.requests(info.requestedPermissions, PERMISSION)
                            || ClientRouting.requests(info.requestedPermissions, ServerConstants.LEGACY_PERMISSION);
                    boolean managed = decision != null && (decision.packages == null || decision.packages.contains(info.packageName));
                    if (!declared && !managed && lastConnected == 0) continue;
                    int authorization = decision != null ? decision.flags & (ConfigManager.MASK_PERMISSION | ShizukuConfig.FLAG_PENDING_COMPANION) : 0;
                    try {
                        DiscoveredApplication app = ApplicationDiscovery.describe(info, authorization, companion, lastConnected);
                        if (app != null) apps.add(app);
                    } catch (RuntimeException e) {
                        if (!failedUsers.contains(user)) failedUsers.add(user);
                        LOGGER.w(e, "Cannot describe application " + info.packageName);
                    }
                }
            } catch (Exception e) {
                if (!failedUsers.contains(user)) failedUsers.add(user);
                LOGGER.w(e, "Cannot discover applications for user " + user);
            }
        }
        reply.writeNoException();
        reply.writeInt(DiscoveredApplication.WIRE_VERSION);
        reply.writeIntArray(failedUsers.stream().mapToInt(Integer::intValue).toArray());
        new ParcelableListSlice<>(apps).writeToParcel(reply, 0);
    }

    Bundle compatibilitySetup(int operation, String snapshot) throws Exception {
        synchronized (clientManager) {
            return new CompatibilitySetupHandler(configManager,
                (uid, value) -> updateFlagsForUid(uid, ConfigManager.MASK_PERMISSION, value),
                () -> {
                    for (int uid : configManager.allowedUids()) reconcileRuntimePermission(uid);
                    mainHandler.post(() -> {
                        BinderSender.resetDelivery();
                        sendBinderToClient();
                    });
                }).execute(operation, snapshot);
        }
    }

    void sendBinderToClient() {
        for (int userId : UserManagerApis.getUserIdsNoThrow()) {
            sendBinderToClient(endpoint, porterEndpoint, userId);
        }
    }

    private static void sendBinderToClient(Binder shizukuBinder, Binder porterBinder, int userId) {
        try {
            Stream<PackageInfo> packages =
                InstalledPackagesCompat.getInstalledPackagesNoThrow(
                    PackageManager.GET_PERMISSIONS, userId
                )
                .stream()
                .filter(pi -> pi != null && pi.requestedPermissions != null)
                .filter(pi -> route(pi) != null);

            LOGGER.i("sending binders");
            packages
                .parallel()
                .forEach(pi -> {
                    ClientRouting.Wire wire = route(pi);
                    sendBinderToUserApp(wire, wire == ClientRouting.Wire.PORTER ? porterBinder : shizukuBinder,
                            pi.packageName, userId);
                });
            LOGGER.i("sent binders");
        } catch (Throwable tr) {
            LOGGER.e("exception when call getInstalledPackages", tr);
        }
    }

    void sendBinderToManager() {
        sendBinderToManager(porterEndpoint);
    }

    private static void sendBinderToManager(Binder binder) {
        for (int userId : UserManagerApis.getUserIdsNoThrow()) {
            sendBinderToManager(binder, userId);
        }
    }

    static void sendBinderToManager(Binder binder, int userId) {
        boolean success = sendBinderToUserApp(ClientRouting.Wire.PORTER, binder, MANAGER_APPLICATION_ID, userId);
        if (!success) {
            // For unknown reason, sometimes this could happens
            // Kill Shizuku app and try again could work
            try {
                LOGGER.e("kill %s in user %d and try again", MANAGER_APPLICATION_ID, userId);
                ActivityManagerApis.forceStopPackageNoThrow(MANAGER_APPLICATION_ID, userId);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {}
                success = sendBinderToUserApp(ClientRouting.Wire.PORTER, binder, MANAGER_APPLICATION_ID, userId);
                if (success) {
                    LOGGER.e("retry succeeded");
                } else {
                    LOGGER.e("retry failed");
                }
            } catch (Throwable tr) {
                LOGGER.e(tr, "retry failed");
            }
        }
    }

    static boolean sendBinderToUserApp(ClientRouting.Wire wire, Binder binder, String packageName, int userId) {
        try {
            DeviceIdleControllerApis.addPowerSaveTempWhitelistApp(packageName, 30 * 1000, userId,
                    316/* PowerExemptionManager#REASON_SHELL */, "shell");
        } catch (Throwable tr) {
            LOGGER.e(tr, "Failed to add %d:%s to power save temp whitelist", userId, packageName);
        }

        if (wire == null) return false;
        String name = packageName + ClientRouting.providerSuffix(wire);
        IContentProvider provider = null;

        /*
         When we pass IBinder through binder (and really crossed process), the receive side (here is system_server process)
         will always get a new instance of android.os.BinderProxy.

         In the implementation of getContentProviderExternal and removeContentProviderExternal, received
         IBinder is used as the key of a HashMap. But hashCode() is not implemented by BinderProxy, so
         removeContentProviderExternal will never work.

         Luckily, we can pass null. When token is token, count will be used.
         */
        IBinder token = null;

        try {
            provider = ActivityManagerApis.getContentProviderExternal(name, userId, token, name);
            if (provider == null) {
                LOGGER.e("provider is null %s %d", name, userId);
                return false;
            }
            if (!provider.asBinder().pingBinder()) {
                LOGGER.e("provider is dead %s %d", name, userId);
                return false;
            }

            Bundle extra = new Bundle();
            String method;
            if (wire == ClientRouting.Wire.PORTER) {
                extra.putBinder(PorterProtocol.DELIVERY_EXTRA_BINDER, binder);
                method = PorterProtocol.DELIVERY_METHOD_SEND_BINDER;
            } else {
                extra.putParcelable("moe.shizuku.privileged.api.intent.extra.BINDER", new BinderContainer(binder));
                method = "sendBinder";
            }

            Bundle reply = IContentProviderCompat.call(provider, null, null, name, method, null, extra);
            if (reply != null) {
                LOGGER.i("send binder to user app %s in user %d", packageName, userId);
                return true;
            } else {
                LOGGER.w("failed to send binder to user app %s in user %d", packageName, userId);
                return false;
            }
        } catch (Throwable tr) {
            LOGGER.e(tr, "failed to send binder to user app %s in user %d", packageName, userId);
            return false;
        } finally {
            if (provider != null) {
                try {
                    ActivityManagerApis.removeContentProviderExternal(name, token);
                } catch (Throwable tr) {
                    LOGGER.w(tr, "removeContentProviderExternal");
                }
            }
        }
    }
}
