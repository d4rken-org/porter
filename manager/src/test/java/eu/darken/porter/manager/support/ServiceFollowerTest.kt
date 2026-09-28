package eu.darken.porter.manager.support

import android.os.Binder
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServiceFollowerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operations = FakeOperations()
    private val directory by lazy { temporary.newFolder() }
    private val binders = MutableStateFlow<IBinder?>(null)
    private val anchors = ClockAnchors(FixedClocks(elapsedMs = 1_000L))

    @After fun cancelEverything() {
        scope.cancel()
    }

    private fun service(pid: Int?) = Binder().also { binder -> pid?.let { synchronized(operations.pids) { operations.pids[binder] = it } } }

    private fun follower(flow: Flow<IBinder?> = binders, deadline: Long = 601_000L) =
        ServiceFollower(flow, operations, directory, deadline, anchors, scope).also { it.start() }

    private fun events() = File(directory, "events.txt").takeIf { it.isFile }?.readText().orEmpty()
    private fun awaitEvent(line: String) = eventually(message = { "missing \"$line\" in:\n${events()}" }) { events().contains(line) }
    private fun count(line: String) = events().lines().count { it.startsWith(line) }
    private fun lineAfter(prefix: String) = events().lines().let { lines -> lines[lines.indexOfFirst { it.startsWith(prefix) } + 1] }

    @Test fun aServiceDeliveredAfterTheStartIsAttached() {
        follower()
        awaitEvent("Waiting for Porter service at ")
        val service = service(4321)

        binders.value = service
        awaitEvent("Server stream attached pid=4321 attach=1 at ")

        assertTrue(events().contains("Service binder arrived attach=1 pid=4321 at "))
        assertEquals(listOf(File(directory, "server-attach-1-pid4321.txt")), operations.metadata)
        val lease = operations.leases.single()
        assertSame(service, lease.binder)
        assertEquals(600_000L, lease.durationMs)
        assertTrue(events().contains("Debug logging granted for 600000ms at "))
        val stream = operations.streams.single()
        assertSame(service, stream.binder)
        assertEquals("1", stream.since)
        assertEquals(1, count("Waiting for Porter service"))
    }

    @Test fun aReplacementMovesTheLeaseAndTheStream() {
        follower()
        val old = service(1)
        val new = service(2)
        binders.value = old
        awaitEvent("Server stream attached pid=1 attach=1")
        val oldLease = operations.leases.single()

        binders.value = new
        awaitEvent("Server stream attached pid=2 attach=2")

        assertEquals(listOf(FakeOperations.Lease(old, oldLease.token, 0)), operations.releases.toList())
        assertTrue(operations.streams[0].isClosed)
        val log = events()
        assertTrue(log, log.indexOf("Server stream ended pid=1") in 0 until log.indexOf("Service replaced at "))
        assertTrue(log.contains("Service binder arrived attach=2 pid=2"))
        assertSame(new, operations.leases[1].binder)
        assertSame(new, operations.streams[1].binder)
        assertTrue(File(directory, "server-attach-2-pid2.txt").isFile)
        assertFalse(operations.streams[1].isClosed)
    }

    @Test fun aLostServiceIsDetached() {
        follower()
        val service = service(4321)
        binders.value = service
        awaitEvent("Server stream attached pid=4321")

        binders.value = null
        awaitEvent("Service binder lost at ")

        assertSame(service, operations.releases.single().binder)
        assertSame(operations.leases.single().token, operations.releases.single().token)
        assertTrue(operations.streams.single().isClosed)
    }

    @Test fun aServiceReturningAfterALossIsNotAReplacement() {
        follower()
        binders.value = service(1)
        awaitEvent("Server stream attached pid=1")
        binders.value = null
        awaitEvent("Service binder lost")

        binders.value = service(2)
        awaitEvent("Server stream attached pid=2 attach=2")

        assertEquals(0, count("Service replaced"))
    }

    @Test fun theSameServiceDeliveredAgainAttachesOnce() {
        val flow = MutableSharedFlow<IBinder?>(replay = 1, extraBufferCapacity = 8)
        follower(flow)
        val service = service(1)
        flow.tryEmit(service)
        awaitEvent("Server stream attached pid=1")

        flow.tryEmit(service)
        val next = service(2)
        flow.tryEmit(next)
        awaitEvent("Server stream attached pid=2")

        assertEquals(listOf("readInfo", "captureMetadata", "requestLease", "openStream", "releaseLease"),
            operations.callsFor(service).map { it.operation })
        assertEquals(2, count("Service binder arrived"))
    }

    @Test fun aFailedLeaseAndStreamLeaveTheFollowerFollowing() {
        follower()
        val broken = service(1)
        operations.grant = { binder, requested -> if (binder === broken) throw IllegalStateException("transaction failed") else requested }
        operations.open = { it !== broken }
        binders.value = broken
        awaitEvent("Server stream unavailable at ")

        assertTrue(events().contains("Debug logging failed at "))

        binders.value = service(2)
        awaitEvent("Server stream attached pid=2 attach=2")
        assertTrue(events().contains("Debug logging granted for "))
    }

    @Test fun aRefusedLeaseIsNotedAsRefused() {
        follower()
        operations.grant = { _, _ -> throw SecurityException("setDebugLogging requires the manager") }
        binders.value = service(1)
        awaitEvent("Server stream attached pid=1")

        assertTrue(events().contains("Debug logging refused at "))
        assertTrue(operations.releases.isEmpty())
    }

    @Test fun aClampedLeaseNotesHowEarlyItExpires() {
        follower()
        operations.grant = { _, _ -> 200_000L }
        binders.value = service(1)
        awaitEvent("Server stream attached pid=1")

        assertTrue(events().contains("Debug logging expires 400000ms early at "))
    }

    @Test fun aRecordingPastItsDeadlineRequestsNoLease() {
        follower(deadline = 1_000L)
        binders.value = service(1)
        awaitEvent("Server stream attached pid=1")

        assertTrue(events().contains("Debug logging skipped: recording ends at deadline at "))
        assertTrue(operations.leases.isEmpty())
    }

    @Test fun aServiceWithoutDiagnosticsAttachesWithoutAStream() {
        follower()
        val service = service(null)
        binders.value = service

        awaitEvent("Server stream unavailable at ")
        assertTrue(events().contains("Service binder arrived attach=1 pid=unknown at "))
        assertEquals(listOf(File(directory, "server-attach-1-unknown.txt")), operations.metadata)
        assertTrue(operations.streams.isEmpty())
    }

    @Test fun everyOperationOfAnAttachUsesTheBinderItWasHanded() {
        follower()
        val first = service(1)
        val second = service(2)
        operations.onReadInfo = { if (it === first) binders.value = second }

        binders.value = first
        awaitEvent("Server stream attached pid=2 attach=2")

        val attach = operations.calls.takeWhile { it.operation != "releaseLease" }
        assertEquals(listOf("readInfo", "captureMetadata", "requestLease", "openStream"), attach.map { it.operation })
        attach.forEach { assertSame(it.operation, first, it.binder) }
        assertSame(first, operations.releases.single().binder)
        assertSame(first, operations.streams[0].binder)
        assertTrue(File(directory, "server-attach-1-pid1.txt").isFile)
    }

    @Test fun aCancelDuringAnAttachReleasesWhatItAcquired() = runBlocking {
        val follower = follower()
        val opening = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        operations.beforeOpenReturns = {
            opening.countDown()
            proceed.await()
        }
        val service = service(1)
        binders.value = service
        assertTrue(opening.await(5, TimeUnit.SECONDS))

        val cancelling = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { follower.cancelAndJoin() }
        proceed.countDown()
        cancelling.join()

        assertSame(operations.leases.single().token, operations.releases.single().token)
        assertTrue(operations.streams.single().isClosed)
        assertFalse(events().contains("Server stream attached"))

        binders.value = service(2)
        Thread.sleep(200)
        assertEquals(1, count("Service binder arrived"))
    }

    @Test fun aServiceThatDoesNotAnswerDetachesAndAttachesNothing() {
        follower()
        val service = service(1)
        binders.value = service
        awaitEvent("Server stream attached pid=1")
        val dead = object : Binder() {
            override fun pingBinder() = false
        }

        binders.value = dead
        awaitEvent("Service binder not answering at ")

        assertSame(service, operations.releases.single().binder)
        assertTrue(operations.streams.single().isClosed)
        assertTrue(operations.callsFor(dead).isEmpty())
        assertEquals(1, count("Service binder arrived"))
    }

    @Test fun aResumedRecordingContinuesTheAttachNumbering() {
        File(directory, "server-attach-3-pid9.txt").writeText("earlier process\n")
        follower()

        binders.value = service(10)
        awaitEvent("Server stream attached pid=10 attach=4")
        assertTrue(File(directory, "server-attach-4-pid10.txt").isFile)
    }

    @Test fun anAttachIsAnchoredRightAfterItsArrival() {
        follower()
        binders.value = service(4321)
        awaitEvent("Server stream attached pid=4321 attach=1")

        assertEquals(anchors.line("attach 1"), lineAfter("Service binder arrived attach=1 pid=4321 at "))
    }

    @Test fun aLossIsAnchoredRightAfterItIsNoted() {
        follower()
        binders.value = service(1)
        awaitEvent("Server stream attached pid=1")

        binders.value = null
        awaitEvent("Clock lost ")

        assertEquals(anchors.line("lost"), lineAfter("Service binder lost at "))
    }

    @Test fun aReplacementIsAnchoredRightAfterItIsNoted() {
        follower()
        binders.value = service(1)
        awaitEvent("Server stream attached pid=1")

        binders.value = service(2)
        awaitEvent("Server stream attached pid=2 attach=2")

        assertEquals(anchors.line("replaced"), lineAfter("Service replaced at "))
        assertEquals(anchors.line("attach 2"), lineAfter("Service binder arrived attach=2 pid=2 at "))
    }
}
