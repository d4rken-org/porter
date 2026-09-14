package rikka.shizuku.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.pm.Signature;
import android.os.Bundle;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import rikka.shizuku.server.util.PackageIdentity;

/**
 * Lane behaviour with injected time, package answers and exit. The oracle is injected rather than
 * mocked statically because Mockito's static mocks are thread-local and would not reach the lane
 * executors from the test thread.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class ApkReconcilerTest {

    private static final String MANAGER = "eu.darken.porter";
    private static final String HOST = "eu.darken.porter.probe";
    private static final String HOST_A = "eu.darken.porter.probe.a";
    private static final String HOST_B = "eu.darken.porter.probe.b";
    private static final int APP_ID = 10123;
    private static final Set<String> MINE = digests("mine");
    private static final Set<String> THEIRS = digests("theirs");

    private static Set<String> digests(String... values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(values)));
    }

    private static PackageIdentity.Result present(int userId, int appId, Set<String> signers) {
        return PackageIdentity.Result.present(
                new PackageIdentity.Observed(userId, appId, signers, false, new Signature[0]));
    }

    private static PackageIdentity.Identity identity(String packageName, int appId, Set<String> signers) {
        return new PackageIdentity.Identity(packageName, appId, signers);
    }

    private static final class Pending {
        final long deadline;
        final Runnable task;

        Pending(long deadline, Runnable task) {
            this.deadline = deadline;
            this.task = task;
        }
    }

    /**
     * Runs the lane's next due task on demand and advances the clock to it. Every submission is
     * retained: the production scheduler hands work to a {@link java.util.concurrent.ScheduledExecutorService},
     * which has no notion of a lane and cancels nothing when a nearer deadline is submitted.
     */
    private static final class TestScheduler implements ApkReconciler.Scheduler {
        long now = 1_000L;
        final Map<String, List<Pending>> queues = new HashMap<>();
        boolean shutdown;

        @Override public synchronized void scheduleAt(String lane, long deadlineMillis, Runnable task) {
            queues.computeIfAbsent(lane, key -> new ArrayList<>()).add(new Pending(deadlineMillis, task));
        }

        @Override public synchronized long now() { return now; }

        @Override public synchronized void shutdown() { shutdown = true; }

        private synchronized Pending earliest(String lane) {
            Pending earliest = null;
            for (Pending pending : queues.getOrDefault(lane, List.of())) {
                if (earliest == null || pending.deadline < earliest.deadline) earliest = pending;
            }
            return earliest;
        }

        /** How many tasks the lane's executor would still hold. */
        int pending(String lane) { return queues.getOrDefault(lane, List.of()).size(); }

        Runnable next(String lane) {
            Pending pending = earliest(lane);
            return pending != null ? pending.task : null;
        }

        long delay(String lane) { return earliest(lane).deadline - now; }

        void fire(String lane) {
            Pending pending = earliest(lane);
            assertNotNull("nothing scheduled on " + lane, pending);
            queues.get(lane).remove(pending);
            now = Math.max(now, pending.deadline);
            pending.task.run();
        }
    }

    private static final class TestOracle implements ApkReconciler.PackageOracle {
        final Map<String, PackageIdentity.Result> answers = new HashMap<>();
        List<Integer> users = List.of(0);
        RuntimeException enumerationFailure;
        final AtomicInteger lookups = new AtomicInteger();

        @Override public PackageIdentity.Result of(String packageName, int userId) {
            lookups.incrementAndGet();
            PackageIdentity.Result result = answers.get(packageName + "@" + userId);
            return result != null ? result : PackageIdentity.Result.absent();
        }

        @Override public List<Integer> userIds() {
            if (enumerationFailure != null) throw enumerationFailure;
            return users;
        }

        void answer(String packageName, int userId, PackageIdentity.Result result) {
            answers.put(packageName + "@" + userId, result);
        }
    }

    private TestScheduler scheduler;
    private TestOracle oracle;
    private List<Integer> exits;
    private ShizukuUserServiceManager userServices;
    private ApkReconciler reconciler;

    @Before
    public void setup() {
        scheduler = new TestScheduler();
        oracle = new TestOracle();
        exits = new ArrayList<>();
        userServices = mock(ShizukuUserServiceManager.class);
        when(userServices.snapshotHosts()).thenReturn(List.of());
        when(userServices.removeIfPresent(any())).thenReturn(true);
        oracle.answer(MANAGER, 0, present(0, APP_ID, MINE));
        reconciler = new ApkReconciler(MANAGER, identity(MANAGER, APP_ID, MINE), userServices,
                oracle, scheduler, code -> exits.add(code));
        reconciler.start();
    }

    private static UserServiceRecord record() {
        return new UserServiceRecord(1, true) {
            @Override public void removeSelf() {}
        };
    }

    private ShizukuUserServiceManager.HostSnapshot host(UserServiceRecord record, PackageIdentity.Identity identity) {
        return new ShizukuUserServiceManager.HostSnapshot(record, identity);
    }

    private void hosts(ShizukuUserServiceManager.HostSnapshot... snapshots) {
        when(userServices.snapshotHosts()).thenReturn(List.of(snapshots));
    }

    // region manager lane

    @Test
    public void aHealthyManagerNeitherExitsNorBacksOff() {
        scheduler.fire(ApkReconciler.LANE_MANAGER);
        assertEquals(List.of(), exits);
        assertEquals(15_000L, scheduler.delay(ApkReconciler.LANE_MANAGER));
    }

    @Test
    public void aLookupFailureNeitherRemovesNorExits() {
        oracle.answer(MANAGER, 0, PackageIdentity.Result.failed(new IllegalStateException("busy")));
        scheduler.fire(ApkReconciler.LANE_MANAGER);
        scheduler.fire(ApkReconciler.LANE_MANAGER);
        assertEquals(List.of(), exits);
    }

    @Test
    public void anAbsentManagerIsConfirmedBeforeExiting() {
        oracle.answer(MANAGER, 0, PackageIdentity.Result.absent());
        scheduler.fire(ApkReconciler.LANE_MANAGER);
        assertEquals(List.of(), exits);
        assertEquals(2_000L, scheduler.delay(ApkReconciler.LANE_MANAGER));

        scheduler.fire(ApkReconciler.LANE_MANAGER);
        assertEquals(List.of(ServerConstants.MANAGER_APP_NOT_FOUND), exits);
    }

    @Test
    public void aManagerThatComesBackCancelsTheCandidate() {
        oracle.answer(MANAGER, 0, PackageIdentity.Result.absent());
        scheduler.fire(ApkReconciler.LANE_MANAGER);
        oracle.answer(MANAGER, 0, present(0, APP_ID, MINE));
        scheduler.fire(ApkReconciler.LANE_MANAGER);
        oracle.answer(MANAGER, 0, PackageIdentity.Result.absent());
        scheduler.fire(ApkReconciler.LANE_MANAGER);

        assertEquals(List.of(), exits);
    }

    @Test
    public void anAbsenceIsNotConfirmedByAFailureInBetween() {
        oracle.answer(MANAGER, 0, PackageIdentity.Result.absent());
        scheduler.fire(ApkReconciler.LANE_MANAGER);
        oracle.answer(MANAGER, 0, PackageIdentity.Result.failed(new IllegalStateException("busy")));
        scheduler.fire(ApkReconciler.LANE_MANAGER);
        assertEquals(List.of(), exits);

        oracle.answer(MANAGER, 0, PackageIdentity.Result.absent());
        scheduler.fire(ApkReconciler.LANE_MANAGER);
        assertEquals(List.of(ServerConstants.MANAGER_APP_NOT_FOUND), exits);
    }

    @Test
    public void aReplacedManagerExitsWithoutGraceAndWithoutRebaselining() {
        oracle.answer(MANAGER, 0, present(0, APP_ID, THEIRS));
        scheduler.fire(ApkReconciler.LANE_MANAGER);
        assertEquals(List.of(ServerConstants.MANAGER_APP_NOT_FOUND), exits);

        // Still measured against the original baseline, so the replacement never becomes healthy.
        scheduler.fire(ApkReconciler.LANE_MANAGER);
        assertEquals(List.of(ServerConstants.MANAGER_APP_NOT_FOUND, ServerConstants.MANAGER_APP_NOT_FOUND), exits);
    }

    @Test
    public void aThrowingTickStillReschedulesBothLanes() {
        hosts(host(record(), identity(HOST, APP_ID, MINE)));
        // Its own lane queues: the reconciler from setup() is still holding deadlines on the old
        // scheduler, and the double retains them all.
        scheduler = new TestScheduler();
        reconciler = new ApkReconciler(MANAGER, identity(MANAGER, APP_ID, MINE), userServices,
                new ApkReconciler.PackageOracle() {
                    @Override public PackageIdentity.Result of(String packageName, int userId) {
                        throw new IllegalStateException("oracle is angry");
                    }

                    @Override public List<Integer> userIds() {
                        throw new IllegalStateException("oracle is angry");
                    }
                }, scheduler, code -> exits.add(code));
        reconciler.start();

        scheduler.fire(ApkReconciler.LANE_MANAGER);
        scheduler.fire(ApkReconciler.LANE_HOST);
        assertNotNull(scheduler.next(ApkReconciler.LANE_MANAGER));
        assertNotNull(scheduler.next(ApkReconciler.LANE_HOST));
        assertEquals(List.of(), exits);
    }

    // endregion

    // region host lane

    @Test
    public void aMatchingHostIsKeptAndTheScanBacksOff() {
        UserServiceRecord record = record();
        hosts(host(record, identity(HOST, APP_ID, MINE)));
        oracle.answer(HOST, 0, present(0, APP_ID, MINE));

        for (long expected : new long[]{30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L}) {
            scheduler.fire(ApkReconciler.LANE_HOST);
            assertEquals(expected, scheduler.delay(ApkReconciler.LANE_HOST));
        }
        verify(userServices, never()).removeIfPresent(any());
    }

    @Test
    public void aRecordCreationBringsTheNextScanBackToTheFloor() {
        UserServiceRecord record = record();
        hosts(host(record, identity(HOST, APP_ID, MINE)));
        oracle.answer(HOST, 0, present(0, APP_ID, MINE));
        scheduler.fire(ApkReconciler.LANE_HOST);
        scheduler.fire(ApkReconciler.LANE_HOST);
        assertEquals(60_000L, scheduler.delay(ApkReconciler.LANE_HOST));

        reconciler.onHostRecordCreated();
        assertEquals(15_000L, scheduler.delay(ApkReconciler.LANE_HOST));
    }

    @Test
    public void anAbsentHostIsRemovedOnlyAfterTheGraceRecheck() {
        UserServiceRecord record = record();
        hosts(host(record, identity(HOST, APP_ID, MINE)));

        scheduler.fire(ApkReconciler.LANE_HOST);
        verify(userServices, never()).removeIfPresent(any());
        assertEquals(2_000L, scheduler.delay(ApkReconciler.LANE_HOST));

        scheduler.fire(ApkReconciler.LANE_HOST);
        verify(userServices).removeIfPresent(record);
    }

    @Test
    public void aHostThatComesBackCancelsItsAbsenceCandidate() {
        UserServiceRecord record = record();
        hosts(host(record, identity(HOST, APP_ID, MINE)));

        scheduler.fire(ApkReconciler.LANE_HOST);
        oracle.answer(HOST, 0, present(0, APP_ID, MINE));
        scheduler.fire(ApkReconciler.LANE_HOST);
        oracle.answers.remove(HOST + "@0");
        scheduler.fire(ApkReconciler.LANE_HOST);

        verify(userServices, never()).removeIfPresent(any());
    }

    @Test
    public void anAbsenceIsNotConfirmedOnAFailedObservation() {
        UserServiceRecord record = record();
        hosts(host(record, identity(HOST, APP_ID, MINE)));

        scheduler.fire(ApkReconciler.LANE_HOST);
        oracle.answer(HOST, 0, PackageIdentity.Result.failed(new IllegalStateException("busy")));
        scheduler.fire(ApkReconciler.LANE_HOST);
        verify(userServices, never()).removeIfPresent(any());

        oracle.answers.remove(HOST + "@0");
        scheduler.fire(ApkReconciler.LANE_HOST);
        verify(userServices).removeIfPresent(record);
    }

    @Test
    public void absenceInOneUserWhileAnotherFailsDoesNotRemove() {
        UserServiceRecord record = record();
        hosts(host(record, identity(HOST, APP_ID, MINE)));
        oracle.users = List.of(0, 10);
        oracle.answer(HOST, 10, PackageIdentity.Result.failed(new IllegalStateException("busy")));

        scheduler.fire(ApkReconciler.LANE_HOST);
        scheduler.fire(ApkReconciler.LANE_HOST);
        verify(userServices, never()).removeIfPresent(any());
    }

    @Test
    public void aSignerMismatchInOneUserRemovesEvenWhileAnotherFails() {
        UserServiceRecord record = record();
        hosts(host(record, identity(HOST, APP_ID, MINE)));
        oracle.users = List.of(0, 10);
        oracle.answer(HOST, 0, present(0, APP_ID, THEIRS));
        oracle.answer(HOST, 10, PackageIdentity.Result.failed(new IllegalStateException("busy")));

        scheduler.fire(ApkReconciler.LANE_HOST);
        verify(userServices).removeIfPresent(record);
    }

    @Test
    public void anOrdinaryUpdateBySameSignerIsNotARemoval() {
        UserServiceRecord record = record();
        hosts(host(record, identity(HOST, APP_ID, MINE)));
        oracle.answer(HOST, 0, present(0, APP_ID, MINE));

        scheduler.fire(ApkReconciler.LANE_HOST);
        scheduler.fire(ApkReconciler.LANE_HOST);
        verify(userServices, never()).removeIfPresent(any());
    }

    @Test
    public void contradictoryIdentitiesAcrossUsersLeaveTheRecordAlone() {
        UserServiceRecord record = record();
        hosts(host(record, identity(HOST, APP_ID, MINE)));
        oracle.users = List.of(0, 10);
        oracle.answer(HOST, 0, present(0, APP_ID, MINE));
        oracle.answer(HOST, 10, present(10, APP_ID, THEIRS));

        scheduler.fire(ApkReconciler.LANE_HOST);
        scheduler.fire(ApkReconciler.LANE_HOST);
        verify(userServices, never()).removeIfPresent(any());
        assertNotNull(scheduler.next(ApkReconciler.LANE_HOST));
    }

    @Test
    public void aHostAbsentInItsOwnUserButMatchingInAnotherIsRetained() {
        UserServiceRecord record = record();
        hosts(host(record, identity(HOST, APP_ID, MINE)));
        oracle.users = List.of(0, 10);
        oracle.answer(HOST, 10, present(10, APP_ID, MINE));

        scheduler.fire(ApkReconciler.LANE_HOST);
        scheduler.fire(ApkReconciler.LANE_HOST);
        verify(userServices, never()).removeIfPresent(any());
    }

    @Test
    public void twoRecordsForOnePackageAreJudgedAgainstTheirOwnIdentities() {
        UserServiceRecord stale = record();
        UserServiceRecord valid = record();
        hosts(host(stale, identity(HOST, APP_ID, THEIRS)), host(valid, identity(HOST, APP_ID, MINE)));
        oracle.answer(HOST, 0, present(0, APP_ID, MINE));

        scheduler.fire(ApkReconciler.LANE_HOST);

        verify(userServices).removeIfPresent(stale);
        verify(userServices, never()).removeIfPresent(valid);
    }

    @Test
    public void oneLookupPerPackageAndUserServesEveryRecord() {
        hosts(host(record(), identity(HOST, APP_ID, MINE)), host(record(), identity(HOST, APP_ID, MINE)));
        oracle.users = List.of(0, 10);
        oracle.answer(HOST, 0, present(0, APP_ID, MINE));
        oracle.answer(HOST, 10, present(10, APP_ID, MINE));

        oracle.lookups.set(0);
        scheduler.fire(ApkReconciler.LANE_HOST);
        assertEquals(2, oracle.lookups.get());
    }

    @Test
    public void aFailedUserEnumerationAbandonsTheScan() {
        UserServiceRecord record = record();
        hosts(host(record, identity(HOST, APP_ID, MINE)));
        oracle.enumerationFailure = new IllegalStateException("user enumeration failed");

        scheduler.fire(ApkReconciler.LANE_HOST);
        scheduler.fire(ApkReconciler.LANE_HOST);
        verify(userServices, never()).removeIfPresent(any());
        assertNotNull(scheduler.next(ApkReconciler.LANE_HOST));
    }

    @Test
    public void aRecordRecreatedSinceTheSnapshotIsNotRemoved() {
        UserServiceRecord record = record();
        hosts(host(record, identity(HOST, APP_ID, MINE)));
        when(userServices.removeIfPresent(record)).thenReturn(false);

        scheduler.fire(ApkReconciler.LANE_HOST);
        scheduler.fire(ApkReconciler.LANE_HOST);

        // The map decides, not the snapshot: removeSelf never ran, and the lane carries on.
        verify(userServices).removeIfPresent(record);
        assertNotNull(scheduler.next(ApkReconciler.LANE_HOST));
    }

    @Test
    public void bringingAScanForwardLeavesTheLaneOnOnePollingChain() {
        UserServiceRecord record = record();
        hosts(host(record, identity(HOST, APP_ID, MINE)));
        oracle.answer(HOST, 0, present(0, APP_ID, MINE));

        // Let the lane settle into a single chain that has backed off well past the floor.
        scheduler.fire(ApkReconciler.LANE_HOST);
        scheduler.fire(ApkReconciler.LANE_HOST);
        assertEquals(60_000L, scheduler.delay(ApkReconciler.LANE_HOST));
        assertEquals(1, scheduler.pending(ApkReconciler.LANE_HOST));

        // A record creation brings the scan forward while the far one is still queued. The executor
        // holds both until they run, so the far one has to retire itself when its turn comes.
        reconciler.onHostRecordCreated();
        assertEquals(15_000L, scheduler.delay(ApkReconciler.LANE_HOST));

        // Run past the superseded deadline; a task that re-arms after being superseded leaves the
        // lane polling on a second chain for the rest of the process's life.
        for (int i = 0; i < 6; i++) scheduler.fire(ApkReconciler.LANE_HOST);

        int chains = scheduler.pending(ApkReconciler.LANE_HOST);
        assertEquals("the host lane is polling on " + chains + " independent chains", 1, chains);
    }

    @Test
    public void anExpiredCandidateWhoseConfirmationFailsDoesNotArmAPastDeadline() {
        UserServiceRecord record = record();
        hosts(host(record, identity(HOST, APP_ID, MINE)));

        // Absent once: a candidate is armed one grace period out.
        scheduler.fire(ApkReconciler.LANE_HOST);
        assertEquals(2_000L, scheduler.delay(ApkReconciler.LANE_HOST));

        // The lane does not get to run at that deadline, and when it finally does the lookup fails,
        // so the candidate is neither confirmed nor cleared. It is now in the past.
        oracle.answer(HOST, 0, PackageIdentity.Result.failed(new IllegalStateException("busy")));
        scheduler.fire(ApkReconciler.LANE_MANAGER);
        scheduler.fire(ApkReconciler.LANE_MANAGER);
        scheduler.fire(ApkReconciler.LANE_HOST);

        long delay = scheduler.delay(ApkReconciler.LANE_HOST);
        assertTrue("host lane armed a deadline " + (-delay) + "ms in the past, which a real "
                + "ScheduledExecutorService runs immediately", delay >= 0);
    }

    @Test
    public void anExpiredCandidateDoesNotSwallowAnotherHostsLiveGrace() {
        UserServiceRecord failing = record();
        UserServiceRecord healthy = record();
        hosts(host(failing, identity(HOST_A, APP_ID, MINE)), host(healthy, identity(HOST_B, APP_ID, MINE)));
        oracle.answer(HOST_B, 0, present(0, APP_ID, MINE));

        // A is absent once and earns a candidate one grace period out; B is healthy.
        scheduler.fire(ApkReconciler.LANE_HOST);
        assertEquals(2_000L, scheduler.delay(ApkReconciler.LANE_HOST));

        // A's confirmation starts failing and the lane only runs again well past A's grace, so A
        // holds a candidate that is now in the past and can be neither confirmed nor cleared.
        oracle.answer(HOST_A, 0, PackageIdentity.Result.failed(new IllegalStateException("busy")));
        scheduler.fire(ApkReconciler.LANE_MANAGER);
        scheduler.fire(ApkReconciler.LANE_MANAGER);
        scheduler.fire(ApkReconciler.LANE_HOST);

        // B goes absent in a later scan and earns its own candidate, one grace period from that scan.
        oracle.answers.remove(HOST_B + "@0");
        scheduler.fire(ApkReconciler.LANE_HOST);

        long delay = scheduler.delay(ApkReconciler.LANE_HOST);
        assertEquals("B's absence is rechecked " + delay + "ms out instead of after its 2000ms grace:"
                + " A's expired candidate won the minimum and the guard then discarded it", 2_000L, delay);
    }

    @Test
    public void hostLookupFailuresAreCounted() {
        UserServiceRecord record = record();
        hosts(host(record, identity(HOST, APP_ID, MINE)));
        // User enumeration answers; every per-user package lookup fails.
        oracle.answer(HOST, 0, PackageIdentity.Result.failed(new IllegalStateException("busy")));

        scheduler.fire(ApkReconciler.LANE_HOST);
        scheduler.fire(ApkReconciler.LANE_HOST);
        scheduler.fire(ApkReconciler.LANE_HOST);

        Bundle out = new Bundle();
        reconciler.writeDiagnostics(out);
        int failures = out.getInt(ServerConstants.DIAGNOSTICS_RECONCILER_HOST_FAILURES);
        assertTrue("three scans of failed lookups reported " + failures + " host failures", failures > 0);
    }

    @Test
    public void aNewRecordInheritsNoPendingCandidate() {
        UserServiceRecord first = record();
        hosts(host(first, identity(HOST, APP_ID, MINE)));
        scheduler.fire(ApkReconciler.LANE_HOST);

        UserServiceRecord second = record();
        hosts(host(second, identity(HOST, APP_ID, MINE)));
        scheduler.fire(ApkReconciler.LANE_HOST);

        verify(userServices, never()).removeIfPresent(any());
    }

    // endregion

    @Test
    public void aWedgedHostLaneDoesNotDelayTheManagerLane() throws Exception {
        CountDownLatch wedged = new CountDownLatch(1);
        CountDownLatch managerRan = new CountDownLatch(1);
        ApkReconciler.ExecutorScheduler executors = new ApkReconciler.ExecutorScheduler();
        try {
            executors.scheduleAt(ApkReconciler.LANE_HOST, executors.now(), () -> {
                try {
                    assertTrue(wedged.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            executors.scheduleAt(ApkReconciler.LANE_MANAGER, executors.now(), managerRan::countDown);

            assertTrue(managerRan.await(10, TimeUnit.SECONDS));
        } finally {
            wedged.countDown();
            executors.shutdown();
        }
    }

    @Test
    public void diagnosticsCarryTheLaneState() {
        UserServiceRecord record = record();
        hosts(host(record, identity(HOST, APP_ID, MINE)));
        oracle.answer(HOST, 0, present(0, APP_ID, THEIRS));
        scheduler.fire(ApkReconciler.LANE_MANAGER);
        scheduler.fire(ApkReconciler.LANE_HOST);

        Bundle out = new Bundle();
        reconciler.writeDiagnostics(out);
        assertTrue(out.getLong(ServerConstants.DIAGNOSTICS_RECONCILER_MANAGER_CHECKED) > 0);
        assertTrue(out.getLong(ServerConstants.DIAGNOSTICS_RECONCILER_HOST_SCANNED) > 0);
        assertTrue(out.getLong(ServerConstants.DIAGNOSTICS_RECONCILER_HOST_DEADLINE) > 0);
        assertEquals(0, out.getInt(ServerConstants.DIAGNOSTICS_RECONCILER_MANAGER_FAILURES));
        assertTrue(out.getString(ServerConstants.DIAGNOSTICS_RECONCILER_LAST_TRIGGER).contains("host replaced"));
    }

    @Test
    public void sustainedLookupFailuresAreCounted() {
        UserServiceRecord record = record();
        hosts(host(record, identity(HOST, APP_ID, MINE)));
        oracle.enumerationFailure = new IllegalStateException("user enumeration failed");
        scheduler.fire(ApkReconciler.LANE_HOST);
        scheduler.fire(ApkReconciler.LANE_HOST);

        oracle.answer(MANAGER, 0, PackageIdentity.Result.failed(new IllegalStateException("busy")));
        scheduler.fire(ApkReconciler.LANE_MANAGER);

        Bundle out = new Bundle();
        reconciler.writeDiagnostics(out);
        assertEquals(2, out.getInt(ServerConstants.DIAGNOSTICS_RECONCILER_HOST_FAILURES));
        assertEquals(1, out.getInt(ServerConstants.DIAGNOSTICS_RECONCILER_MANAGER_FAILURES));
        assertFalse(exits.contains(ServerConstants.MANAGER_APP_NOT_FOUND));
    }

    @Test
    public void theStartupVerdictTreatsAFailedLookupLikeAnAbsentManager() {
        assertEquals(0, ShizukuService.managerStartupExitCode(present(0, APP_ID, MINE)));
        assertEquals(ServerConstants.MANAGER_APP_NOT_FOUND,
                ShizukuService.managerStartupExitCode(PackageIdentity.Result.absent()));
        assertEquals(ServerConstants.MANAGER_APP_NOT_FOUND,
                ShizukuService.managerStartupExitCode(PackageIdentity.Result.failed(new IllegalStateException("busy"))));
    }
}
