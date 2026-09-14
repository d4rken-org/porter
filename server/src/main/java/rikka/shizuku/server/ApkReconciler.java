package rikka.shizuku.server;

import android.content.pm.UserInfo;
import android.os.Bundle;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import rikka.hidden.compat.UserManagerApis;
import rikka.shizuku.server.util.Logger;
import rikka.shizuku.server.util.PackageIdentity;

/**
 * Polls for the manager and for user-service hosts disappearing or being replaced, on monotonic
 * deadlines, instead of watching their APK paths. The inotify watch the server used to arm is denied
 * to {@code u:r:shell:s0} and {@code startWatching()} reports nothing, so under the ADB start path
 * the server outlived the removal of the app that owned it.
 *
 * <p>Removing a record asks the service to shut down. It does not terminate the process: what this
 * guarantees is that the server stops vouching for a service and stops handing out its binder.
 */
public final class ApkReconciler {

    private static final Logger LOGGER = new Logger("ApkReconciler");

    public static final String LANE_MANAGER = "manager";
    public static final String LANE_HOST = "host";

    /** Detection latency is the security-relevant property here, so this lane never backs off. */
    private static final long MANAGER_INTERVAL_MILLIS = 15_000L;
    /** A second reading before acting on an absence, in either lane. */
    private static final long GRACE_MILLIS = 2_000L;
    private static final long[] HOST_BACKOFF_MILLIS = {15_000L, 30_000L, 60_000L, 120_000L, 240_000L, 300_000L};
    /** Every failure is logged up to here, then one in this many, so a stuck lane stays visible. */
    private static final int FAILURE_LOG_EVERY = 10;

    private static final long NOT_SCHEDULED = Long.MIN_VALUE;

    public interface Scheduler {
        void scheduleAt(String lane, long deadlineMillis, Runnable task);

        /** Monotonic; must survive a wall-clock change. */
        long now();

        void shutdown();
    }

    public interface ExitHandler {
        void exit(int code);
    }

    /**
     * Injected rather than reached statically: Mockito's static mocks are thread-local and would not
     * reach the lane executors from the test thread.
     */
    public interface PackageOracle {
        PackageIdentity.Result of(String packageName, int userId);

        /** Throws when enumeration failed, which is not the same as "no users". */
        List<Integer> userIds();
    }

    private final String managerPackageName;
    private final PackageIdentity.Identity managerBaseline;
    private final ShizukuUserServiceManager userServices;
    private final PackageOracle oracle;
    private final Scheduler scheduler;
    private final ExitHandler exitHandler;

    private final AtomicLong hostDeadline = new AtomicLong(NOT_SCHEDULED);
    private volatile int hostBackoff;
    private volatile boolean managerAbsenceCandidate;
    private volatile long lastManagerCheck;
    private volatile long lastHostScan;
    private volatile int managerFailures;
    private volatile int hostFailures;
    private volatile String lastTrigger = "";

    /** Keyed by record identity, so a record created since the last scan inherits no candidate. */
    private final Map<UserServiceRecord, Long> absenceCandidates = new IdentityHashMap<>();

    public ApkReconciler(String managerPackageName, PackageIdentity.Identity managerBaseline,
                         ShizukuUserServiceManager userServices, PackageOracle oracle,
                         Scheduler scheduler, ExitHandler exitHandler) {
        this.managerPackageName = managerPackageName;
        this.managerBaseline = managerBaseline;
        this.userServices = userServices;
        this.oracle = oracle;
        this.scheduler = scheduler;
        this.exitHandler = exitHandler;
    }

    public void start() {
        scheduler.scheduleAt(LANE_MANAGER, scheduler.now() + MANAGER_INTERVAL_MILLIS, this::managerTick);
        armHost(scheduler.now() + HOST_BACKOFF_MILLIS[0]);
    }

    public void shutdown() {
        scheduler.shutdown();
    }

    /** A new record is the one moment a host scan is worth bringing forward to the floor. */
    public void onHostRecordCreated() {
        hostBackoff = 0;
        armHost(scheduler.now() + HOST_BACKOFF_MILLIS[0]);
    }

    // region manager lane

    void managerTick() {
        try {
            checkManager();
        } catch (Throwable tr) {
            LOGGER.w(tr, "manager lane");
        } finally {
            long delay = managerAbsenceCandidate ? GRACE_MILLIS : MANAGER_INTERVAL_MILLIS;
            scheduler.scheduleAt(LANE_MANAGER, scheduler.now() + delay, this::managerTick);
        }
    }

    private void checkManager() {
        // User 0 only, matching the lookup the server starts from.
        PackageIdentity.Result result = oracle.of(managerPackageName, 0);
        lastManagerCheck = scheduler.now();

        switch (result.state) {
            case PRESENT:
                managerFailures = 0;
                if (managerBaseline.matches(result.observed)) {
                    managerAbsenceCandidate = false;
                    return;
                }
                // Never rebaselined: managerAppId authorises every manager call, so accepting the new
                // installation here would hand the old app id's authority to whoever replaced it.
                trigger("manager replaced", managerPackageName, 0, difference(managerBaseline, result.observed), LANE_MANAGER);
                exitHandler.exit(ServerConstants.MANAGER_APP_NOT_FOUND);
                return;
            case ABSENT:
                managerFailures = 0;
                if (!managerAbsenceCandidate) {
                    managerAbsenceCandidate = true;
                    return;
                }
                trigger("manager absent", managerPackageName, 0, "absent in user 0", LANE_MANAGER);
                exitHandler.exit(ServerConstants.MANAGER_APP_NOT_FOUND);
                return;
            case LOOKUP_FAILED:
            default:
                // Neither confirms nor cancels a pending candidate.
                countFailure(LANE_MANAGER, ++managerFailures, result.cause);
        }
    }

    // endregion

    // region host lane

    void hostTick() {
        // Cleared before the work, not inside it: a scan that throws must still leave the lane
        // able to arm its next deadline.
        hostDeadline.set(NOT_SCHEDULED);
        boolean changed = false;
        try {
            changed = scanHosts();
        } catch (Throwable tr) {
            LOGGER.w(tr, "host lane");
        } finally {
            hostBackoff = changed ? 0 : Math.min(hostBackoff + 1, HOST_BACKOFF_MILLIS.length - 1);
            long next = scheduler.now() + HOST_BACKOFF_MILLIS[hostBackoff];
            long candidate = earliestCandidate();
            // Only a candidate still ahead of us: one whose grace has passed while confirmation kept
            // failing stays in the map, and arming it would ask the executor for a past deadline,
            // which it runs immediately - the lane would busy-loop against the package manager.
            armHost(candidate > scheduler.now() ? Math.min(next, candidate) : next);
        }
    }

    private boolean scanHosts() {
        List<ShizukuUserServiceManager.HostSnapshot> hosts = userServices.snapshotHosts();
        lastHostScan = scheduler.now();
        forgetCandidatesOutside(hosts);
        if (hosts.isEmpty()) return false;

        List<Integer> users;
        try {
            users = oracle.userIds();
        } catch (Throwable tr) {
            // Concluding anything from a failed enumeration would read a package living only in a
            // user we never asked about as absent.
            countFailure(LANE_HOST, ++hostFailures, tr);
            return false;
        }

        // One lookup per distinct (package, user) for this scan; the verdict is still computed per
        // record, because one package can carry several records with different recorded identities.
        Map<String, PackageIdentity.Result> lookups = new HashMap<>();
        boolean changed = false;
        for (ShizukuUserServiceManager.HostSnapshot host : hosts) {
            if (reduce(host, users, lookups)) changed = true;
        }

        // A package lookup that never answers stalls cleanup just as completely as a failed
        // enumeration, and each record only ever sees its own as a local verdict. Counting the scan
        // once here is what keeps that visible in diagnostics instead of reporting zero forever.
        PackageIdentity.Result failed = firstFailure(lookups);
        if (failed == null) hostFailures = 0;
        else countFailure(LANE_HOST, ++hostFailures, failed.cause);
        return changed;
    }

    private static PackageIdentity.Result firstFailure(Map<String, PackageIdentity.Result> lookups) {
        for (PackageIdentity.Result result : lookups.values()) {
            if (result.state == PackageIdentity.State.LOOKUP_FAILED) return result;
        }
        return null;
    }

    /** @return whether this record's state changed, which brings the next scan back to the floor. */
    private boolean reduce(ShizukuUserServiceManager.HostSnapshot host, List<Integer> users,
                           Map<String, PackageIdentity.Result> lookups) {
        List<PackageIdentity.Observed> present = new ArrayList<>();
        boolean anyFailed = false;
        for (int userId : users) {
            PackageIdentity.Result result = lookups.computeIfAbsent(
                    host.packageName + "@" + userId, key -> oracle.of(host.packageName, userId));
            switch (result.state) {
                case PRESENT: present.add(result.observed); break;
                case LOOKUP_FAILED: anyFailed = true; break;
                default: break;
            }
        }

        if (!present.isEmpty()) {
            PackageIdentity.Observed sample = present.get(0);
            for (PackageIdentity.Observed other : present) {
                if (!PackageIdentity.sameInstallation(sample, other)) {
                    LOGGER.w("Unstable scan for %s: %s and %s disagree", host.packageName, sample, other);
                    return false;
                }
            }
            if (host.identity.matches(sample)) {
                // Nothing is learned or updated; the identity was fixed when the record was created.
                return clearCandidate(host.record);
            }
            // Identity is package-global, so one user's mismatch proves replacement no matter what
            // the other users answered. This is positive evidence, not absence.
            trigger("host replaced", host.packageName, sample.userId, difference(host.identity, sample), LANE_HOST);
            return remove(host);
        }

        if (anyFailed) return false;

        long now = scheduler.now();
        Long candidateSince;
        synchronized (absenceCandidates) {
            candidateSince = absenceCandidates.get(host.record);
            if (candidateSince == null) {
                absenceCandidates.put(host.record, now + GRACE_MILLIS);
                return true;
            }
        }
        if (now < candidateSince) return false;
        trigger("host absent", host.packageName, -1, "absent in every user", LANE_HOST);
        return remove(host);
    }

    private boolean remove(ShizukuUserServiceManager.HostSnapshot host) {
        clearCandidate(host.record);
        // The record may have been removed and its key recreated since the snapshot; only the map it
        // is actually in decides.
        return userServices.removeIfPresent(host.record);
    }

    private boolean clearCandidate(UserServiceRecord record) {
        synchronized (absenceCandidates) {
            return absenceCandidates.remove(record) != null;
        }
    }

    private void forgetCandidatesOutside(List<ShizukuUserServiceManager.HostSnapshot> hosts) {
        Map<UserServiceRecord, Boolean> live = new IdentityHashMap<>();
        for (ShizukuUserServiceManager.HostSnapshot host : hosts) live.put(host.record, Boolean.TRUE);
        synchronized (absenceCandidates) {
            absenceCandidates.keySet().retainAll(live.keySet());
        }
    }

    private long earliestCandidate() {
        long earliest = NOT_SCHEDULED;
        synchronized (absenceCandidates) {
            for (long deadline : absenceCandidates.values()) {
                if (earliest == NOT_SCHEDULED || deadline < earliest) earliest = deadline;
            }
        }
        return earliest;
    }

    /** Never assigns blindly: a scan finishing next to a record creation must not lose its reset. */
    private void armHost(long deadline) {
        while (true) {
            long current = hostDeadline.get();
            if (current != NOT_SCHEDULED && current <= deadline) return;
            if (hostDeadline.compareAndSet(current, deadline)) {
                scheduler.scheduleAt(LANE_HOST, deadline, this::hostTick);
                return;
            }
        }
    }

    // endregion

    private void countFailure(String lane, int consecutive, Throwable cause) {
        if (consecutive <= 1 || consecutive % FAILURE_LOG_EVERY == 0) {
            LOGGER.w(cause, "%s lane: %d consecutive lookup failures", lane, consecutive);
        }
    }

    private void trigger(String reason, String packageName, int userId, String detail, String lane) {
        lastTrigger = reason + " " + packageName + (userId >= 0 ? " user " + userId : "") + " (" + detail + ") via " + lane;
        LOGGER.w("%s", lastTrigger);
    }

    private static String difference(PackageIdentity.Identity recorded, PackageIdentity.Observed observed) {
        if (recorded.appId != observed.appId) {
            return "app id " + recorded.appId + " -> " + observed.appId;
        }
        return "signer " + recorded.signerDigests + " -> " + observed.signerDigests;
    }

    public void writeDiagnostics(Bundle out) {
        out.putLong(ServerConstants.DIAGNOSTICS_RECONCILER_MANAGER_CHECKED, lastManagerCheck);
        out.putLong(ServerConstants.DIAGNOSTICS_RECONCILER_HOST_SCANNED, lastHostScan);
        long deadline = hostDeadline.get();
        out.putLong(ServerConstants.DIAGNOSTICS_RECONCILER_HOST_DEADLINE, deadline == NOT_SCHEDULED ? -1 : deadline);
        out.putInt(ServerConstants.DIAGNOSTICS_RECONCILER_MANAGER_FAILURES, managerFailures);
        out.putInt(ServerConstants.DIAGNOSTICS_RECONCILER_HOST_FAILURES, hostFailures);
        out.putString(ServerConstants.DIAGNOSTICS_RECONCILER_LAST_TRIGGER, lastTrigger);
    }

    /** The manager lane must never wait behind a host scan, so each lane gets its own thread. */
    public static final class ExecutorScheduler implements Scheduler {
        private final Map<String, ScheduledExecutorService> lanes = new HashMap<>();

        public ExecutorScheduler() {
            for (String lane : new String[]{LANE_MANAGER, LANE_HOST}) {
                lanes.put(lane, Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "porter-reconcile-" + lane);
                    thread.setDaemon(true);
                    return thread;
                }));
            }
        }

        @Override
        public void scheduleAt(String lane, long deadlineMillis, Runnable task) {
            long delay = Math.max(0, deadlineMillis - now());
            lanes.get(lane).schedule(task, delay, TimeUnit.MILLISECONDS);
        }

        @Override
        public long now() {
            return SystemClock.elapsedRealtime();
        }

        @Override
        public void shutdown() {
            for (ScheduledExecutorService executor : lanes.values()) executor.shutdownNow();
        }
    }

    public static final class SystemPackageOracle implements PackageOracle {
        @Override
        public PackageIdentity.Result of(String packageName, int userId) {
            return PackageIdentity.of(packageName, userId);
        }

        @Override
        public List<Integer> userIds() {
            try {
                // The throwing variant: getUserIdsNoThrow() answers {0} when enumeration fails, and a
                // silent narrowing to user 0 would read a package living only in user 10 as absent.
                List<UserInfo> users = UserManagerApis.getUsers(true, true, true);
                List<Integer> ids = new ArrayList<>(users.size());
                for (UserInfo user : users) ids.add(user.id);
                return ids;
            } catch (Throwable tr) {
                throw new IllegalStateException("user enumeration failed", tr);
            }
        }
    }
}
