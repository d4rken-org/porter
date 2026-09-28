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
        serviceOperations = operations,
        serviceStates = serviceStates,
        describeDevice = { "device" },
        wallClock = wallClock,
    )

    private fun service(pid: Int) = Binder().also { synchronized(operations.pids) { operations.pids[it] = pid } }

    private fun session() = store.sessions().single()
    private fun events() = File(store.directory(session().id), "events.txt").readText()

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
}
