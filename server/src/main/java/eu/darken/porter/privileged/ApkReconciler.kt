package eu.darken.porter.privileged

import android.os.Bundle
import android.os.SystemClock
import eu.darken.porter.privileged.util.PackageIdentity
import java.util.IdentityHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import rikka.hidden.compat.UserManagerApis
import rikka.shizuku.server.UserServiceRecord
import rikka.shizuku.server.util.Logger

/**
 * Polls for the manager and for user-service hosts disappearing or being replaced, on monotonic
 * deadlines, instead of watching their APK paths. The inotify watch the server used to arm is denied
 * to `u:r:shell:s0` and `startWatching()` reports nothing, so under the ADB start path
 * the server outlived the removal of the app that owned it.
 *
 * Removing a record asks the service to shut down. It does not terminate the process: what this
 * guarantees is that the server stops vouching for a service and stops handing out its binder.
 */
class ApkReconciler(
    private val managerPackageName: String,
    /** The manager installation the server started with; never updated. */
    val managerBaseline: PackageIdentity.Identity,
    private val userServices: ShizukuUserServiceManager,
    private val oracle: PackageOracle,
    private val scheduler: Scheduler,
    private val exitHandler: ExitHandler,
) {

    interface Scheduler {
        fun scheduleAt(lane: String, deadlineMillis: Long, task: Runnable)

        /** Monotonic; must survive a wall-clock change. */
        fun now(): Long

        fun shutdown()
    }

    fun interface ExitHandler {
        fun exit(code: Int)
    }

    /**
     * Injected rather than reached statically: Mockito's static mocks are thread-local and would not
     * reach the lane executors from the test thread.
     */
    interface PackageOracle {
        fun of(packageName: String, userId: Int): PackageIdentity.Result

        /** Throws when enumeration failed, which is not the same as "no users". */
        fun userIds(): List<Int>
    }

    private val hostDeadline = AtomicLong(NOT_SCHEDULED)

    @Volatile
    private var hostBackoff = 0

    @Volatile
    private var managerAbsenceCandidate = false

    @Volatile
    private var lastManagerCheck = 0L

    @Volatile
    private var lastHostScan = 0L

    @Volatile
    private var managerFailures = 0

    @Volatile
    private var hostFailures = 0

    @Volatile
    private var lastTrigger = ""

    /** Keyed by record identity, so a record created since the last scan inherits no candidate. */
    private val absenceCandidates: MutableMap<UserServiceRecord, Long> = IdentityHashMap()

    fun start() {
        scheduler.scheduleAt(LANE_MANAGER, scheduler.now() + MANAGER_INTERVAL_MILLIS, ::managerTick)
        armHost(scheduler.now() + HOST_BACKOFF_MILLIS[0])
    }

    fun shutdown() {
        scheduler.shutdown()
    }

    /** A new record is the one moment a host scan is worth bringing forward to the floor. */
    fun onHostRecordCreated() {
        hostBackoff = 0
        armHost(scheduler.now() + HOST_BACKOFF_MILLIS[0])
    }

    // region manager lane

    internal fun managerTick() {
        try {
            checkManager()
        } catch (tr: Throwable) {
            LOGGER.w(tr, "manager lane")
        } finally {
            val delay = if (managerAbsenceCandidate) GRACE_MILLIS else MANAGER_INTERVAL_MILLIS
            scheduler.scheduleAt(LANE_MANAGER, scheduler.now() + delay, ::managerTick)
        }
    }

    private fun checkManager() {
        // User 0 only, matching the lookup the server starts from.
        val result = oracle.of(managerPackageName, 0)
        lastManagerCheck = scheduler.now()

        when (result.state) {
            PackageIdentity.State.PRESENT -> {
                managerFailures = 0
                if (managerBaseline.matches(result.observed)) {
                    managerAbsenceCandidate = false
                    return
                }
                // Never rebaselined: managerAppId authorises every manager call, so accepting the new
                // installation here would hand the old app id's authority to whoever replaced it.
                trigger("manager replaced", managerPackageName, 0, difference(managerBaseline, result.observed!!), LANE_MANAGER)
                exitHandler.exit(ServerConstants.MANAGER_APP_NOT_FOUND)
            }
            PackageIdentity.State.ABSENT -> {
                managerFailures = 0
                if (!managerAbsenceCandidate) {
                    managerAbsenceCandidate = true
                    return
                }
                trigger("manager absent", managerPackageName, 0, "absent in user 0", LANE_MANAGER)
                exitHandler.exit(ServerConstants.MANAGER_APP_NOT_FOUND)
            }
            PackageIdentity.State.LOOKUP_FAILED -> {
                // Neither confirms nor cancels a pending candidate.
                countFailure(LANE_MANAGER, ++managerFailures, result.cause)
            }
        }
    }

    // endregion

    // region host lane

    internal fun hostTick() {
        var changed = false
        try {
            changed = scanHosts()
        } catch (tr: Throwable) {
            LOGGER.w(tr, "host lane")
        } finally {
            hostBackoff = if (changed) 0 else minOf(hostBackoff + 1, HOST_BACKOFF_MILLIS.size - 1)
            val now = scheduler.now()
            val next = now + HOST_BACKOFF_MILLIS[hostBackoff]
            val candidate = earliestCandidateAfter(now)
            armHost(if (candidate == NOT_SCHEDULED) next else minOf(next, candidate))
        }
    }

    private fun scanHosts(): Boolean {
        val hosts = userServices.snapshotHosts()
        lastHostScan = scheduler.now()
        forgetCandidatesOutside(hosts)
        if (hosts.isEmpty()) return false

        val users: List<Int>
        try {
            users = oracle.userIds()
        } catch (tr: Throwable) {
            // Concluding anything from a failed enumeration would read a package living only in a
            // user we never asked about as absent.
            countFailure(LANE_HOST, ++hostFailures, tr)
            return false
        }

        // One lookup per distinct (package, user) for this scan; the verdict is still computed per
        // record, because one package can carry several records with different recorded identities.
        val lookups = HashMap<String, PackageIdentity.Result>()
        var changed = false
        for (host in hosts) {
            if (reduce(host, users, lookups)) changed = true
        }

        // A package lookup that never answers stalls cleanup just as completely as a failed
        // enumeration, and each record only ever sees its own as a local verdict. Counting the scan
        // once here is what keeps that visible in diagnostics instead of reporting zero forever.
        val failed = firstFailure(lookups)
        if (failed == null) hostFailures = 0
        else countFailure(LANE_HOST, ++hostFailures, failed.cause)
        return changed
    }

    /** @return whether this record's state changed, which brings the next scan back to the floor. */
    private fun reduce(
        host: ShizukuUserServiceManager.HostSnapshot,
        users: List<Int>,
        lookups: MutableMap<String, PackageIdentity.Result>,
    ): Boolean {
        val present = ArrayList<PackageIdentity.Observed>()
        var anyFailed = false
        for (userId in users) {
            val result = lookups.getOrPut(host.packageName + "@" + userId) { oracle.of(host.packageName, userId) }
            when (result.state) {
                PackageIdentity.State.PRESENT -> present.add(result.observed!!)
                PackageIdentity.State.LOOKUP_FAILED -> anyFailed = true
                else -> {}
            }
        }

        if (present.isNotEmpty()) {
            val sample = present[0]
            for (other in present) {
                if (!PackageIdentity.sameInstallation(sample, other)) {
                    LOGGER.w("Unstable scan for %s: %s and %s disagree", host.packageName, sample, other)
                    return false
                }
            }
            if (host.identity.matches(sample)) {
                // Nothing is learned or updated; the identity was fixed when the record was created.
                return clearCandidate(host.record)
            }
            // Identity is package-global, so one user's mismatch proves replacement no matter what
            // the other users answered. This is positive evidence, not absence.
            trigger("host replaced", host.packageName, sample.userId, difference(host.identity, sample), LANE_HOST)
            return remove(host)
        }

        if (anyFailed) return false

        val now = scheduler.now()
        val candidateSince = synchronized(absenceCandidates) {
            absenceCandidates[host.record] ?: run {
                absenceCandidates[host.record] = now + GRACE_MILLIS
                return true
            }
        }
        if (now < candidateSince) return false
        trigger("host absent", host.packageName, -1, "absent in every user", LANE_HOST)
        return remove(host)
    }

    private fun remove(host: ShizukuUserServiceManager.HostSnapshot): Boolean {
        clearCandidate(host.record)
        // The record may have been removed and its key recreated since the snapshot; only the map it
        // is actually in decides.
        return userServices.removeIfPresent(host.record)
    }

    private fun clearCandidate(record: UserServiceRecord): Boolean = synchronized(absenceCandidates) {
        absenceCandidates.remove(record) != null
    }

    private fun forgetCandidatesOutside(hosts: List<ShizukuUserServiceManager.HostSnapshot>) {
        val live: MutableMap<UserServiceRecord, Boolean> = IdentityHashMap()
        for (host in hosts) live[host.record] = true
        synchronized(absenceCandidates) {
            absenceCandidates.keys.retainAll(live.keys)
        }
    }

    /**
     * Earliest absence deadline still ahead of [now]. An expired one stays in the map, because
     * confirmation still needs it, but it is not a deadline to arm: the executor runs a past deadline
     * immediately, and letting it win this minimum would drop another host's live grace with it.
     */
    private fun earliestCandidateAfter(now: Long): Long {
        var earliest = NOT_SCHEDULED
        synchronized(absenceCandidates) {
            for (deadline in absenceCandidates.values) {
                if (deadline <= now) continue
                if (earliest == NOT_SCHEDULED || deadline < earliest) earliest = deadline
            }
        }
        return earliest
    }

    /** Never assigns blindly: a scan finishing next to a record creation must not lose its reset. */
    private fun armHost(deadline: Long) {
        while (true) {
            val current = hostDeadline.get()
            if (current != NOT_SCHEDULED && current <= deadline) return
            if (hostDeadline.compareAndSet(current, deadline)) {
                // Nothing cancels the task this supersedes, so each task retires itself: it ticks
                // only while it is still the armed deadline, and otherwise returns without
                // rescheduling. A task that re-armed unconditionally would leave the lane polling on
                // one more chain for the rest of the process's life.
                scheduler.scheduleAt(LANE_HOST, deadline) {
                    if (hostDeadline.compareAndSet(deadline, NOT_SCHEDULED)) hostTick()
                }
                return
            }
        }
    }

    // endregion

    private fun countFailure(lane: String, consecutive: Int, cause: Throwable?) {
        if (consecutive <= 1 || consecutive % FAILURE_LOG_EVERY == 0) {
            LOGGER.w(cause, "%s lane: %d consecutive lookup failures", lane, consecutive)
        }
    }

    private fun trigger(reason: String, packageName: String, userId: Int, detail: String, lane: String) {
        lastTrigger = reason + " " + packageName + (if (userId >= 0) " user $userId" else "") + " (" + detail + ") via " + lane
        LOGGER.w("%s", lastTrigger)
    }

    fun writeDiagnostics(out: Bundle) {
        out.putLong(ServerConstants.DIAGNOSTICS_RECONCILER_MANAGER_CHECKED, lastManagerCheck)
        out.putLong(ServerConstants.DIAGNOSTICS_RECONCILER_HOST_SCANNED, lastHostScan)
        val deadline = hostDeadline.get()
        out.putLong(ServerConstants.DIAGNOSTICS_RECONCILER_HOST_DEADLINE, if (deadline == NOT_SCHEDULED) -1 else deadline)
        out.putInt(ServerConstants.DIAGNOSTICS_RECONCILER_MANAGER_FAILURES, managerFailures)
        out.putInt(ServerConstants.DIAGNOSTICS_RECONCILER_HOST_FAILURES, hostFailures)
        out.putString(ServerConstants.DIAGNOSTICS_RECONCILER_LAST_TRIGGER, lastTrigger)
    }

    /** The manager lane must never wait behind a host scan, so each lane gets its own thread. */
    class ExecutorScheduler : Scheduler {
        private val lanes = HashMap<String, ScheduledExecutorService>()

        init {
            for (lane in arrayOf(LANE_MANAGER, LANE_HOST)) {
                lanes[lane] = Executors.newSingleThreadScheduledExecutor { runnable ->
                    val thread = Thread(runnable, "porter-reconcile-$lane")
                    thread.isDaemon = true
                    thread
                }
            }
        }

        override fun scheduleAt(lane: String, deadlineMillis: Long, task: Runnable) {
            val delay = maxOf(0, deadlineMillis - now())
            lanes.getValue(lane).schedule(task, delay, TimeUnit.MILLISECONDS)
        }

        override fun now(): Long = SystemClock.elapsedRealtime()

        override fun shutdown() {
            for (executor in lanes.values) executor.shutdownNow()
        }
    }

    class SystemPackageOracle : PackageOracle {
        override fun of(packageName: String, userId: Int): PackageIdentity.Result = PackageIdentity.of(packageName, userId)

        override fun userIds(): List<Int> {
            try {
                // The throwing variant: getUserIdsNoThrow() answers {0} when enumeration fails, and a
                // silent narrowing to user 0 would read a package living only in user 10 as absent.
                val users = UserManagerApis.getUsers(true, true, true)
                val ids = ArrayList<Int>(users.size)
                for (user in users) ids.add(user.id)
                return ids
            } catch (tr: Throwable) {
                throw IllegalStateException("user enumeration failed", tr)
            }
        }
    }

    companion object {
        private val LOGGER = Logger("ApkReconciler")

        const val LANE_MANAGER = "manager"
        const val LANE_HOST = "host"

        /** Detection latency is the security-relevant property here, so this lane never backs off. */
        private const val MANAGER_INTERVAL_MILLIS = 15_000L

        /** A second reading before acting on an absence, in either lane. */
        private const val GRACE_MILLIS = 2_000L
        private val HOST_BACKOFF_MILLIS = longArrayOf(15_000L, 30_000L, 60_000L, 120_000L, 240_000L, 300_000L)

        /** Every failure is logged up to here, then one in this many, so a stuck lane stays visible. */
        private const val FAILURE_LOG_EVERY = 10

        private const val NOT_SCHEDULED = Long.MIN_VALUE

        private fun firstFailure(lookups: Map<String, PackageIdentity.Result>): PackageIdentity.Result? {
            for (result in lookups.values) {
                if (result.state == PackageIdentity.State.LOOKUP_FAILED) return result
            }
            return null
        }

        private fun difference(recorded: PackageIdentity.Identity, observed: PackageIdentity.Observed): String {
            if (recorded.appId != observed.appId) {
                return "app id " + recorded.appId + " -> " + observed.appId
            }
            return "signer " + recorded.signerDigests + " -> " + observed.signerDigests
        }
    }
}
