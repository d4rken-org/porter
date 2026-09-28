package eu.darken.porter.manager.support

import android.app.Application
import android.os.Binder
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import eu.darken.porter.manager.utils.PorterStateMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** How a recording starts and ends the service follower it runs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DebugRecorderFollowTest {
    @get:Rule val temporary = TemporaryFolder()
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store by lazy { DebugLogStore(temporary.newFolder()) }
    private val operations = FakeOperations()
    private val binders = MutableStateFlow<IBinder?>(null)
    private val states = MutableStateFlow(PorterStateMachine.State.STOPPED)
    private val clocks = FixedClocks()
    private val anchors = ClockAnchors(clocks)

    @After fun cancelEverything() {
        scope.cancel()
    }

    private fun recorder(
        serviceStates: () -> Flow<PorterStateMachine.State> = { states },
        wallClock: () -> Long = System::currentTimeMillis,
    ) = DebugRecorder(
        application,
        storeOverride = store,
        scope = scope,
        managerLog = { FakeProcess() },
        binders = binders,
        anchors = anchors,
        serviceOperations = operations,
        serviceStates = serviceStates,
        describeDevice = { "device" },
        wallClock = wallClock,
    )

    private fun service(pid: Int) = Binder().also { synchronized(operations.pids) { operations.pids[it] = pid } }

    private fun session() = store.sessions().single()
    private fun events() = File(store.directory(session().id), "events.txt").readText()
    private fun lineAfter(prefix: String) = events().lines().let { lines -> lines[lines.indexOfFirst { it.startsWith(prefix) } + 1] }

    @Test fun stopReleasesTheServiceAndStopsFollowing() = runBlocking {
        val recorder = recorder()
        recorder.start()
        val service = service(4321)
        binders.value = service
        eventually(message = { events() }) { events().contains("Server stream attached pid=4321") }

        recorder.stop()

        assertNull(store.activeId())
        assertSame(service, operations.releases.single().binder)
        assertSame(operations.leases.single().token, operations.releases.single().token)
        assertTrue(operations.streams.single().isClosed)

        val later = service(99)
        binders.value = later
        Thread.sleep(200)
        assertTrue(operations.callsFor(later).isEmpty())
    }

    @Test fun aStartFailingAfterTheFollowerStartedCleansUp() = runBlocking {
        val service = service(4321)
        binders.value = service
        val recorder = recorder(serviceStates = {
            eventually { operations.streams.isNotEmpty() }
            throw IllegalStateException("state machine unavailable")
        })

        try {
            recorder.start()
            fail("start should have failed")
        } catch (e: IllegalStateException) {
            assertEquals("state machine unavailable", e.message)
        }

        assertNull(store.activeId())
        assertFalse(recorder.state.value.active)
        assertSame(service, operations.releases.single().binder)
        assertTrue(operations.streams.single().isClosed)

        val later = service(99)
        binders.value = later
        Thread.sleep(200)
        assertTrue(operations.callsFor(later).isEmpty())
    }

    @Test fun stopWaitsForAStateAppendInProgress() = runBlocking {
        val holding = AtomicBoolean(false)
        val appending = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val recorder = recorder(wallClock = {
            if (holding.getAndSet(false)) {
                appending.countDown()
                proceed.await()
            }
            System.currentTimeMillis()
        })
        recorder.start()
        eventually(message = { events() }) { events().contains("Service STOPPED at ") }
        holding.set(true)
        states.value = PorterStateMachine.State.RUNNING
        assertTrue(appending.await(5, TimeUnit.SECONDS))

        val stopping = launch(Dispatchers.IO) { recorder.stop() }
        Thread.sleep(300)
        assertFalse(stopping.isCompleted)
        assertNotNull(store.activeId())

        proceed.countDown()
        stopping.join()

        assertNull(store.activeId())
        assertTrue(events().contains("Service RUNNING at "))
    }

    @Test fun aNewSessionIsAnchoredAsAStart() = runBlocking {
        val recorder = recorder()
        recorder.start()

        assertEquals(anchors.line("start"), lineAfter("Recording manager pid="))
        recorder.stop()
    }

    @Test fun aSessionContinuedByANewProcessIsAnchoredAsAResume() = runBlocking {
        store.create()
        val recorder = recorder()

        recorder.attach()
        eventually { recorder.state.value.active }

        assertEquals(anchors.line("resume"), lineAfter("Recording manager pid="))
        assertTrue(events().lines().none { it.startsWith("Clock start ") })
        recorder.stop()
    }

    @Test fun stopIsAnchoredAfterTheFollowerEndsAndBeforeTheSessionFinishes() = runBlocking {
        val recorder = recorder()
        recorder.start()
        binders.value = service(4321)
        eventually(message = { events() }) { events().contains("Server stream attached pid=4321") }
        val id = session().id
        val activeAtRead = CopyOnWriteArrayList<String?>()
        clocks.onRead = { activeAtRead += store.activeId() }

        recorder.stop()

        assertEquals(id, activeAtRead.first())
        val lines = File(store.directory(id), "events.txt").readText().trimEnd().lines()
        assertEquals(anchors.line("stop"), lines.last())
        assertTrue(lines.joinToString("\n"), lines.indexOfFirst { it.startsWith("Server stream ended pid=4321 ") } in 0 until lines.lastIndex)
        assertEquals("Elapsed: 1000", File(store.directory(id), "server-stop.txt").readLines()[1])
    }
}
