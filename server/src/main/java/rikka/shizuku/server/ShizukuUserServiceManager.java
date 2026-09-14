package rikka.shizuku.server;

import android.content.pm.PackageInfo;
import android.util.ArrayMap;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import moe.shizuku.starter.ServiceStarter;
import rikka.shizuku.server.util.PackageIdentity;

public class ShizukuUserServiceManager extends UserServiceManager {

    /**
     * The identity each live record was created for. Membership is the liveness test, and nothing is
     * ever learned or updated after creation: a record either belongs to the installation that asked
     * for it or it does not.
     */
    private final Map<UserServiceRecord, PackageIdentity.Identity> hostIdentities = new ArrayMap<>();

    private volatile boolean accessPaused;

    /**
     * Set once during construction. The manager needs the reconciler to bring the next host scan
     * forward on record creation and the reconciler needs the manager for its snapshot, so one of
     * them has to be wired after the other exists. Not null-guarded: a null here would be a
     * construction-order bug worth failing on, not a deadline worth skipping.
     */
    private volatile ApkReconciler reconciler;

    public ShizukuUserServiceManager() {
        super();
    }

    /** One live record and the identity it was created for. */
    public static final class HostSnapshot {
        public final UserServiceRecord record;
        public final String packageName;
        public final PackageIdentity.Identity identity;

        HostSnapshot(UserServiceRecord record, PackageIdentity.Identity identity) {
            this.record = record;
            this.packageName = identity.packageName;
            this.identity = identity;
        }
    }

    public void setReconciler(ApkReconciler reconciler) {
        this.reconciler = reconciler;
    }

    @Override
    public String getUserServiceStartCmd(
            UserServiceRecord record, String key, String token, String packageName,
            String classname, String processNameSuffix, int callingUid, boolean use32Bits, boolean debug) {

        if (accessPaused) throw new SecurityException("App access is paused");
        String appProcess = "/system/bin/app_process";
        if (use32Bits && new File("/system/bin/app_process32").exists()) {
            appProcess = "/system/bin/app_process32";
        }
        return ServiceStarter.commandForUserService(
                appProcess,
                ShizukuService.getManagerApplicationInfo().sourceDir,
                ShizukuService.MANAGER_APPLICATION_ID,
                token, packageName, classname, processNameSuffix, callingUid, debug);
    }

    public synchronized void setAccessPaused(boolean paused) {
        accessPaused = paused;
        if (paused) {
            for (UserServiceRecord record : new ArrayList<>(hostIdentities.keySet())) record.removeSelf();
        }
    }

    @Override
    public synchronized boolean canReuseUserServiceRecord(UserServiceRecord record, PackageInfo packageInfo) {
        PackageIdentity.Identity recorded = hostIdentities.get(record);
        // An unidentified record and an unreadable identity are both "cannot prove a match", which is
        // not the same as a match.
        if (recorded == null) return false;
        return recorded.matches(PackageIdentity.observe(packageInfo, 0));
    }

    @Override
    public synchronized void onUserServiceRecordCreated(UserServiceRecord record, PackageInfo packageInfo) {
        if (accessPaused) {
            record.removeSelf();
            throw new SecurityException("App access is paused");
        }
        super.onUserServiceRecordCreated(record, packageInfo);

        // From the PackageInfo that authorised this bind: a second lookup here would run under the
        // monitor on a binder thread with the record half registered, and an unidentified record has
        // no good disposition.
        hostIdentities.put(record, PackageIdentity.identityOf(packageInfo));
        reconciler.onHostRecordCreated();
    }

    @Override
    public synchronized void onUserServiceRecordRemoved(UserServiceRecord record) {
        super.onUserServiceRecordRemoved(record);
        hostIdentities.remove(record);
    }

    public synchronized List<HostSnapshot> snapshotHosts() {
        List<HostSnapshot> snapshot = new ArrayList<>(hostIdentities.size());
        for (Map.Entry<UserServiceRecord, PackageIdentity.Identity> entry : hostIdentities.entrySet()) {
            snapshot.add(new HostSnapshot(entry.getKey(), entry.getValue()));
        }
        return snapshot;
    }

    /** Reentrant with {@link UserServiceRecord#removeSelf()}, which takes this same monitor. */
    public synchronized boolean removeIfPresent(UserServiceRecord record) {
        if (!hostIdentities.containsKey(record)) return false;
        record.removeSelf();
        return true;
    }
}
