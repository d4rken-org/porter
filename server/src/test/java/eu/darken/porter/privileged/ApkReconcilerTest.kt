package eu.darken.porter.privileged

import android.content.pm.Signature
import android.os.Bundle
import eu.darken.porter.privileged.util.PackageIdentity
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import rikka.shizuku.server.UserServiceRecord

/**
 * Lane behaviour with injected time, package answers and exit. The oracle is injected rather than
 * mocked statically because Mockito's static mocks are thread-local and would not reach the lane
 * executors from the test thread.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ApkReconcilerTest {

    private class Pending(val deadline: Long, val task: Runnable)

    /**
     * Runs the lane's next due task on demand and advances the clock to it. Every submission is
     * retained: the production scheduler hands work to a [java.util.concurrent.ScheduledExecutorService],
     * which has no notion of a lane and cancels nothing when a nearer deadline is submitted.
     */
    private class TestScheduler : ApkReconciler.Scheduler {
        var now = 1_000L
        val queues = HashMap<String, MutableList<Pending>>()
        var shutdown = false

        @Synchronized
        override fun scheduleAt(lane: String, deadlineMillis: Long, task: Runnable) {
            queues.getOrPut(lane) { ArrayList() }.add(Pending(deadlineMillis, task))
        }

        @Synchronized
        override fun now(): Long = now

        @Synchronized
        override fun shutdown() {
            shutdown = true
        }

        @Synchronized
        private fun earliest(lane: String): Pending? {
            var earliest: Pending? = null
            for (pending in queues[lane] ?: emptyList()) {
                if (earliest == null || pending.deadline < earliest.deadline) earliest = pending
            }
            return earliest
        }

        /** How many tasks the lane's executor would still hold. */
        fun pending(lane: String): Int = (queues[lane] ?: emptyList()).size

        fun next(lane: String): Runnable? = earliest(lane)?.task

        fun delay(lane: String): Long = earliest(lane)!!.deadline - now

        fun fire(lane: String) {
            val pending = earliest(lane)
            assertNotNull("nothing scheduled on $lane", pending)
            queues.getValue(lane).remove(pending!!)
            now = maxOf(now, pending.deadline)
            pending.task.run()
        }
    }

    private class TestOracle : ApkReconciler.PackageOracle {
        val answers = HashMap<String, PackageIdentity.Result>()
        var users: List<Int> = listOf(0)
        var enumerationFailure: RuntimeException? = null
        val lookups = AtomicInteger()

        override fun of(packageName: String, userId: Int): PackageIdentity.Result {
            lookups.incrementAndGet()
            return answers["$packageName@$userId"] ?: PackageIdentity.Result.absent()
        }

        override fun userIds(): List<Int> {
            enumerationFailure?.let { throw it }
            return users
        }

        fun answer(packageName: String, userId: Int, result: PackageIdentity.Result) {
            answers["$packageName@$userId"] = result
        }
    }

    private lateinit var scheduler: TestScheduler
    private lateinit var oracle: TestOracle
    private lateinit var exits: MutableList<Int>
    private lateinit var userServices: ShizukuUserServiceManager
    private lateinit var reconciler: ApkReconciler

    @Before
    fun setup() {
        scheduler = TestScheduler()
        oracle = TestOracle()
        exits = ArrayList()
        userServices = mock(ShizukuUserServiceManager::class.java)
        `when`(userServices.snapshotHosts()).thenReturn(emptyList())
        `when`(userServices.removeIfPresent(anyRecord())).thenReturn(true)
        oracle.answer(MANAGER, 0, present(0, APP_ID, MINE))
        reconciler = ApkReconciler(MANAGER, identity(MANAGER, APP_ID, MINE), userServices, oracle, scheduler) { code -> exits.add(code) }
        reconciler.start()
    }

    private fun host(record: UserServiceRecord, identity: PackageIdentity.Identity): ShizukuUserServiceManager.HostSnapshot =
        ShizukuUserServiceManager.HostSnapshot(record, identity)

    private fun hosts(vararg snapshots: ShizukuUserServiceManager.HostSnapshot) {
        `when`(userServices.snapshotHosts()).thenReturn(snapshots.toList())
    }

    // region manager lane

    @Test
    fun aHealthyManagerNeitherExitsNorBacksOff() {
        scheduler.fire(ApkReconciler.LANE_MANAGER)
        assertEquals(emptyList<Int>(), exits)
        assertEquals(15_000L, scheduler.delay(ApkReconciler.LANE_MANAGER))
    }

    @Test
    fun aLookupFailureNeitherRemovesNorExits() {
        oracle.answer(MANAGER, 0, PackageIdentity.Result.failed(IllegalStateException("busy")))
        scheduler.fire(ApkReconciler.LANE_MANAGER)
        scheduler.fire(ApkReconciler.LANE_MANAGER)
        assertEquals(emptyList<Int>(), exits)
    }

    @Test
    fun anAbsentManagerIsConfirmedBeforeExiting() {
        oracle.answer(MANAGER, 0, PackageIdentity.Result.absent())
        scheduler.fire(ApkReconciler.LANE_MANAGER)
        assertEquals(emptyList<Int>(), exits)
        assertEquals(2_000L, scheduler.delay(ApkReconciler.LANE_MANAGER))

        scheduler.fire(ApkReconciler.LANE_MANAGER)
        assertEquals(listOf(ServerConstants.MANAGER_APP_NOT_FOUND), exits)
    }

    @Test
    fun aManagerThatComesBackCancelsTheCandidate() {
        oracle.answer(MANAGER, 0, PackageIdentity.Result.absent())
        scheduler.fire(ApkReconciler.LANE_MANAGER)
        oracle.answer(MANAGER, 0, present(0, APP_ID, MINE))
        scheduler.fire(ApkReconciler.LANE_MANAGER)
        oracle.answer(MANAGER, 0, PackageIdentity.Result.absent())
        scheduler.fire(ApkReconciler.LANE_MANAGER)

        assertEquals(emptyList<Int>(), exits)
    }

    @Test
    fun anAbsenceIsNotConfirmedByAFailureInBetween() {
        oracle.answer(MANAGER, 0, PackageIdentity.Result.absent())
        scheduler.fire(ApkReconciler.LANE_MANAGER)
        oracle.answer(MANAGER, 0, PackageIdentity.Result.failed(IllegalStateException("busy")))
        scheduler.fire(ApkReconciler.LANE_MANAGER)
        assertEquals(emptyList<Int>(), exits)

        oracle.answer(MANAGER, 0, PackageIdentity.Result.absent())
        scheduler.fire(ApkReconciler.LANE_MANAGER)
        assertEquals(listOf(ServerConstants.MANAGER_APP_NOT_FOUND), exits)
    }

    @Test
    fun aReplacedManagerExitsWithoutGraceAndWithoutRebaselining() {
        oracle.answer(MANAGER, 0, present(0, APP_ID, THEIRS))
        scheduler.fire(ApkReconciler.LANE_MANAGER)
        assertEquals(listOf(ServerConstants.MANAGER_APP_NOT_FOUND), exits)

        // Still measured against the original baseline, so the replacement never becomes healthy.
        scheduler.fire(ApkReconciler.LANE_MANAGER)
        assertEquals(listOf(ServerConstants.MANAGER_APP_NOT_FOUND, ServerConstants.MANAGER_APP_NOT_FOUND), exits)
    }

    @Test
    fun aThrowingTickStillReschedulesBothLanes() {
        hosts(host(record(), identity(HOST, APP_ID, MINE)))
        // Its own lane queues: the reconciler from setup() is still holding deadlines on the old
        // scheduler, and the double retains them all.
        scheduler = TestScheduler()
        val angry = object : ApkReconciler.PackageOracle {
            override fun of(packageName: String, userId: Int): PackageIdentity.Result =
                throw IllegalStateException("oracle is angry")

            override fun userIds(): List<Int> = throw IllegalStateException("oracle is angry")
        }
        reconciler = ApkReconciler(MANAGER, identity(MANAGER, APP_ID, MINE), userServices, angry, scheduler) { code -> exits.add(code) }
        reconciler.start()

        scheduler.fire(ApkReconciler.LANE_MANAGER)
        scheduler.fire(ApkReconciler.LANE_HOST)
        assertNotNull(scheduler.next(ApkReconciler.LANE_MANAGER))
        assertNotNull(scheduler.next(ApkReconciler.LANE_HOST))
        assertEquals(emptyList<Int>(), exits)
    }

    // endregion

    // region host lane

    @Test
    fun aMatchingHostIsKeptAndTheScanBacksOff() {
        val record = record()
        hosts(host(record, identity(HOST, APP_ID, MINE)))
        oracle.answer(HOST, 0, present(0, APP_ID, MINE))

        for (expected in longArrayOf(30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L)) {
            scheduler.fire(ApkReconciler.LANE_HOST)
            assertEquals(expected, scheduler.delay(ApkReconciler.LANE_HOST))
        }
        verify(userServices, never()).removeIfPresent(anyRecord())
    }

    @Test
    fun aRecordCreationBringsTheNextScanBackToTheFloor() {
        val record = record()
        hosts(host(record, identity(HOST, APP_ID, MINE)))
        oracle.answer(HOST, 0, present(0, APP_ID, MINE))
        scheduler.fire(ApkReconciler.LANE_HOST)
        scheduler.fire(ApkReconciler.LANE_HOST)
        assertEquals(60_000L, scheduler.delay(ApkReconciler.LANE_HOST))

        reconciler.onHostRecordCreated()
        assertEquals(15_000L, scheduler.delay(ApkReconciler.LANE_HOST))
    }

    @Test
    fun anAbsentHostIsRemovedOnlyAfterTheGraceRecheck() {
        val record = record()
        hosts(host(record, identity(HOST, APP_ID, MINE)))

        scheduler.fire(ApkReconciler.LANE_HOST)
        verify(userServices, never()).removeIfPresent(anyRecord())
        assertEquals(2_000L, scheduler.delay(ApkReconciler.LANE_HOST))

        scheduler.fire(ApkReconciler.LANE_HOST)
        verify(userServices).removeIfPresent(record)
    }

    @Test
    fun aHostThatComesBackCancelsItsAbsenceCandidate() {
        val record = record()
        hosts(host(record, identity(HOST, APP_ID, MINE)))

        scheduler.fire(ApkReconciler.LANE_HOST)
        oracle.answer(HOST, 0, present(0, APP_ID, MINE))
        scheduler.fire(ApkReconciler.LANE_HOST)
        oracle.answers.remove("$HOST@0")
        scheduler.fire(ApkReconciler.LANE_HOST)

        verify(userServices, never()).removeIfPresent(anyRecord())
    }

    @Test
    fun anAbsenceIsNotConfirmedOnAFailedObservation() {
        val record = record()
        hosts(host(record, identity(HOST, APP_ID, MINE)))

        scheduler.fire(ApkReconciler.LANE_HOST)
        oracle.answer(HOST, 0, PackageIdentity.Result.failed(IllegalStateException("busy")))
        scheduler.fire(ApkReconciler.LANE_HOST)
        verify(userServices, never()).removeIfPresent(anyRecord())

        oracle.answers.remove("$HOST@0")
        scheduler.fire(ApkReconciler.LANE_HOST)
        verify(userServices).removeIfPresent(record)
    }

    @Test
    fun absenceInOneUserWhileAnotherFailsDoesNotRemove() {
        val record = record()
        hosts(host(record, identity(HOST, APP_ID, MINE)))
        oracle.users = listOf(0, 10)
        oracle.answer(HOST, 10, PackageIdentity.Result.failed(IllegalStateException("busy")))

        scheduler.fire(ApkReconciler.LANE_HOST)
        scheduler.fire(ApkReconciler.LANE_HOST)
        verify(userServices, never()).removeIfPresent(anyRecord())
    }

    @Test
    fun aSignerMismatchInOneUserRemovesEvenWhileAnotherFails() {
        val record = record()
        hosts(host(record, identity(HOST, APP_ID, MINE)))
        oracle.users = listOf(0, 10)
        oracle.answer(HOST, 0, present(0, APP_ID, THEIRS))
        oracle.answer(HOST, 10, PackageIdentity.Result.failed(IllegalStateException("busy")))

        scheduler.fire(ApkReconciler.LANE_HOST)
        verify(userServices).removeIfPresent(record)
    }

    @Test
    fun anOrdinaryUpdateBySameSignerIsNotARemoval() {
        val record = record()
        hosts(host(record, identity(HOST, APP_ID, MINE)))
        oracle.answer(HOST, 0, present(0, APP_ID, MINE))

        scheduler.fire(ApkReconciler.LANE_HOST)
        scheduler.fire(ApkReconciler.LANE_HOST)
        verify(userServices, never()).removeIfPresent(anyRecord())
    }

    @Test
    fun contradictoryIdentitiesAcrossUsersLeaveTheRecordAlone() {
        val record = record()
        hosts(host(record, identity(HOST, APP_ID, MINE)))
        oracle.users = listOf(0, 10)
        oracle.answer(HOST, 0, present(0, APP_ID, MINE))
        oracle.answer(HOST, 10, present(10, APP_ID, THEIRS))

        scheduler.fire(ApkReconciler.LANE_HOST)
        scheduler.fire(ApkReconciler.LANE_HOST)
        verify(userServices, never()).removeIfPresent(anyRecord())
        assertNotNull(scheduler.next(ApkReconciler.LANE_HOST))
    }

    @Test
    fun aHostAbsentInItsOwnUserButMatchingInAnotherIsRetained() {
        val record = record()
        hosts(host(record, identity(HOST, APP_ID, MINE)))
        oracle.users = listOf(0, 10)
        oracle.answer(HOST, 10, present(10, APP_ID, MINE))

        scheduler.fire(ApkReconciler.LANE_HOST)
        scheduler.fire(ApkReconciler.LANE_HOST)
        verify(userServices, never()).removeIfPresent(anyRecord())
    }

    @Test
    fun twoRecordsForOnePackageAreJudgedAgainstTheirOwnIdentities() {
        val stale = record()
        val valid = record()
        hosts(host(stale, identity(HOST, APP_ID, THEIRS)), host(valid, identity(HOST, APP_ID, MINE)))
        oracle.answer(HOST, 0, present(0, APP_ID, MINE))

        scheduler.fire(ApkReconciler.LANE_HOST)

        verify(userServices).removeIfPresent(stale)
        verify(userServices, never()).removeIfPresent(valid)
    }

    @Test
    fun oneLookupPerPackageAndUserServesEveryRecord() {
        hosts(host(record(), identity(HOST, APP_ID, MINE)), host(record(), identity(HOST, APP_ID, MINE)))
        oracle.users = listOf(0, 10)
        oracle.answer(HOST, 0, present(0, APP_ID, MINE))
        oracle.answer(HOST, 10, present(10, APP_ID, MINE))

        oracle.lookups.set(0)
        scheduler.fire(ApkReconciler.LANE_HOST)
        assertEquals(2, oracle.lookups.get())
    }

    @Test
    fun aFailedUserEnumerationAbandonsTheScan() {
        val record = record()
        hosts(host(record, identity(HOST, APP_ID, MINE)))
        oracle.enumerationFailure = IllegalStateException("user enumeration failed")

        scheduler.fire(ApkReconciler.LANE_HOST)
        scheduler.fire(ApkReconciler.LANE_HOST)
        verify(userServices, never()).removeIfPresent(anyRecord())
        assertNotNull(scheduler.next(ApkReconciler.LANE_HOST))
    }

    @Test
    fun aRecordRecreatedSinceTheSnapshotIsNotRemoved() {
        val record = record()
        hosts(host(record, identity(HOST, APP_ID, MINE)))
        `when`(userServices.removeIfPresent(record)).thenReturn(false)

        scheduler.fire(ApkReconciler.LANE_HOST)
        scheduler.fire(ApkReconciler.LANE_HOST)

        // The map decides, not the snapshot: removeSelf never ran, and the lane carries on.
        verify(userServices).removeIfPresent(record)
        assertNotNull(scheduler.next(ApkReconciler.LANE_HOST))
    }

    @Test
    fun bringingAScanForwardLeavesTheLaneOnOnePollingChain() {
        val record = record()
        hosts(host(record, identity(HOST, APP_ID, MINE)))
        oracle.answer(HOST, 0, present(0, APP_ID, MINE))

        // Let the lane settle into a single chain that has backed off well past the floor.
        scheduler.fire(ApkReconciler.LANE_HOST)
        scheduler.fire(ApkReconciler.LANE_HOST)
        assertEquals(60_000L, scheduler.delay(ApkReconciler.LANE_HOST))
        assertEquals(1, scheduler.pending(ApkReconciler.LANE_HOST))

        // A record creation brings the scan forward while the far one is still queued. The executor
        // holds both until they run, so the far one has to retire itself when its turn comes.
        reconciler.onHostRecordCreated()
        assertEquals(15_000L, scheduler.delay(ApkReconciler.LANE_HOST))

        // Run past the superseded deadline; a task that re-arms after being superseded leaves the
        // lane polling on a second chain for the rest of the process's life.
        repeat(6) { scheduler.fire(ApkReconciler.LANE_HOST) }

        val chains = scheduler.pending(ApkReconciler.LANE_HOST)
        assertEquals("the host lane is polling on $chains independent chains", 1, chains)
    }

    @Test
    fun anExpiredCandidateWhoseConfirmationFailsDoesNotArmAPastDeadline() {
        val record = record()
        hosts(host(record, identity(HOST, APP_ID, MINE)))

        // Absent once: a candidate is armed one grace period out.
        scheduler.fire(ApkReconciler.LANE_HOST)
        assertEquals(2_000L, scheduler.delay(ApkReconciler.LANE_HOST))

        // The lane does not get to run at that deadline, and when it finally does the lookup fails,
        // so the candidate is neither confirmed nor cleared. It is now in the past.
        oracle.answer(HOST, 0, PackageIdentity.Result.failed(IllegalStateException("busy")))
        scheduler.fire(ApkReconciler.LANE_MANAGER)
        scheduler.fire(ApkReconciler.LANE_MANAGER)
        scheduler.fire(ApkReconciler.LANE_HOST)

        val delay = scheduler.delay(ApkReconciler.LANE_HOST)
        assertTrue(
            "host lane armed a deadline " + (-delay) + "ms in the past, which a real " +
                "ScheduledExecutorService runs immediately",
            delay >= 0,
        )
    }

    @Test
    fun anExpiredCandidateDoesNotSwallowAnotherHostsLiveGrace() {
        val failing = record()
        val healthy = record()
        hosts(host(failing, identity(HOST_A, APP_ID, MINE)), host(healthy, identity(HOST_B, APP_ID, MINE)))
        oracle.answer(HOST_B, 0, present(0, APP_ID, MINE))

        // A is absent once and earns a candidate one grace period out; B is healthy.
        scheduler.fire(ApkReconciler.LANE_HOST)
        assertEquals(2_000L, scheduler.delay(ApkReconciler.LANE_HOST))

        // A's confirmation starts failing and the lane only runs again well past A's grace, so A
        // holds a candidate that is now in the past and can be neither confirmed nor cleared.
        oracle.answer(HOST_A, 0, PackageIdentity.Result.failed(IllegalStateException("busy")))
        scheduler.fire(ApkReconciler.LANE_MANAGER)
        scheduler.fire(ApkReconciler.LANE_MANAGER)
        scheduler.fire(ApkReconciler.LANE_HOST)

        // B goes absent in a later scan and earns its own candidate, one grace period from that scan.
        oracle.answers.remove("$HOST_B@0")
        scheduler.fire(ApkReconciler.LANE_HOST)

        val delay = scheduler.delay(ApkReconciler.LANE_HOST)
        assertEquals(
            "B's absence is rechecked " + delay + "ms out instead of after its 2000ms grace:" +
                " A's expired candidate won the minimum and the guard then discarded it",
            2_000L, delay,
        )
    }

    @Test
    fun hostLookupFailuresAreCounted() {
        val record = record()
        hosts(host(record, identity(HOST, APP_ID, MINE)))
        // User enumeration answers; every per-user package lookup fails.
        oracle.answer(HOST, 0, PackageIdentity.Result.failed(IllegalStateException("busy")))

        scheduler.fire(ApkReconciler.LANE_HOST)
        scheduler.fire(ApkReconciler.LANE_HOST)
        scheduler.fire(ApkReconciler.LANE_HOST)

        val out = Bundle()
        reconciler.writeDiagnostics(out)
        val failures = out.getInt(ServerConstants.DIAGNOSTICS_RECONCILER_HOST_FAILURES)
        assertTrue("three scans of failed lookups reported $failures host failures", failures > 0)
    }

    @Test
    fun aNewRecordInheritsNoPendingCandidate() {
        val first = record()
        hosts(host(first, identity(HOST, APP_ID, MINE)))
        scheduler.fire(ApkReconciler.LANE_HOST)

        val second = record()
        hosts(host(second, identity(HOST, APP_ID, MINE)))
        scheduler.fire(ApkReconciler.LANE_HOST)

        verify(userServices, never()).removeIfPresent(anyRecord())
    }

    // endregion

    @Test
    fun aWedgedHostLaneDoesNotDelayTheManagerLane() {
        val wedged = CountDownLatch(1)
        val managerRan = CountDownLatch(1)
        val executors = ApkReconciler.ExecutorScheduler()
        try {
            executors.scheduleAt(ApkReconciler.LANE_HOST, executors.now()) {
                try {
                    assertTrue(wedged.await(10, TimeUnit.SECONDS))
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            executors.scheduleAt(ApkReconciler.LANE_MANAGER, executors.now()) { managerRan.countDown() }

            assertTrue(managerRan.await(10, TimeUnit.SECONDS))
        } finally {
            wedged.countDown()
            executors.shutdown()
        }
    }

    @Test
    fun diagnosticsCarryTheLaneState() {
        val record = record()
        hosts(host(record, identity(HOST, APP_ID, MINE)))
        oracle.answer(HOST, 0, present(0, APP_ID, THEIRS))
        scheduler.fire(ApkReconciler.LANE_MANAGER)
        scheduler.fire(ApkReconciler.LANE_HOST)

        val out = Bundle()
        reconciler.writeDiagnostics(out)
        assertTrue(out.getLong(ServerConstants.DIAGNOSTICS_RECONCILER_MANAGER_CHECKED) > 0)
        assertTrue(out.getLong(ServerConstants.DIAGNOSTICS_RECONCILER_HOST_SCANNED) > 0)
        assertTrue(out.getLong(ServerConstants.DIAGNOSTICS_RECONCILER_HOST_DEADLINE) > 0)
        assertEquals(0, out.getInt(ServerConstants.DIAGNOSTICS_RECONCILER_MANAGER_FAILURES))
        assertTrue(out.getString(ServerConstants.DIAGNOSTICS_RECONCILER_LAST_TRIGGER)!!.contains("host replaced"))
    }

    @Test
    fun sustainedLookupFailuresAreCounted() {
        val record = record()
        hosts(host(record, identity(HOST, APP_ID, MINE)))
        oracle.enumerationFailure = IllegalStateException("user enumeration failed")
        scheduler.fire(ApkReconciler.LANE_HOST)
        scheduler.fire(ApkReconciler.LANE_HOST)

        oracle.answer(MANAGER, 0, PackageIdentity.Result.failed(IllegalStateException("busy")))
        scheduler.fire(ApkReconciler.LANE_MANAGER)

        val out = Bundle()
        reconciler.writeDiagnostics(out)
        assertEquals(2, out.getInt(ServerConstants.DIAGNOSTICS_RECONCILER_HOST_FAILURES))
        assertEquals(1, out.getInt(ServerConstants.DIAGNOSTICS_RECONCILER_MANAGER_FAILURES))
        assertFalse(exits.contains(ServerConstants.MANAGER_APP_NOT_FOUND))
    }

    @Test
    fun theStartupVerdictTreatsAFailedLookupLikeAnAbsentManager() {
        assertEquals(0, PorterServer.managerStartupExitCode(present(0, APP_ID, MINE)))
        assertEquals(
            ServerConstants.MANAGER_APP_NOT_FOUND,
            PorterServer.managerStartupExitCode(PackageIdentity.Result.absent()),
        )
        assertEquals(
            ServerConstants.MANAGER_APP_NOT_FOUND,
            PorterServer.managerStartupExitCode(PackageIdentity.Result.failed(IllegalStateException("busy"))),
        )
    }

    private companion object {
        const val MANAGER = "eu.darken.porter"
        const val HOST = "eu.darken.porter.probe"
        const val HOST_A = "eu.darken.porter.probe.a"
        const val HOST_B = "eu.darken.porter.probe.b"
        const val APP_ID = 10123
        val MINE = digests("mine")
        val THEIRS = digests("theirs")

        fun digests(vararg values: String): Set<String> = Collections.unmodifiableSet(LinkedHashSet(values.asList()))

        fun present(userId: Int, appId: Int, signers: Set<String>): PackageIdentity.Result =
            PackageIdentity.Result.present(PackageIdentity.Observed(userId, appId, signers, false, arrayOf<Signature>()))

        fun identity(packageName: String, appId: Int, signers: Set<String>): PackageIdentity.Identity =
            PackageIdentity.Identity(packageName, appId, signers)

        fun record(): UserServiceRecord = object : UserServiceRecord(1, true) {
            override fun removeSelf() {}
        }

        /** `any()` answers null, which Kotlin's non-null parameter refuses before Mockito records the matcher. */
        fun anyRecord(): UserServiceRecord {
            any<UserServiceRecord>()
            return record()
        }
    }
}
