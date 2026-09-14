package moe.shizuku.manager.utils

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import moe.shizuku.manager.utils.ShizukuStateMachine.State
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** One misbehaving subscriber must not silence the notifications every other one depends on. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShizukuStateMachineThrowingListenerTest {

    private val machine = ShizukuStateMachine()
    private val observed = mutableListOf<State>()
    private val thrower = mutableListOf<State>()

    @Test fun aThrowingListenerDoesNotStopDeliveryToTheOthers() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        // A throw inside runTest's own scope would fail the test instead of being observed by it.
        val failing = CoroutineScope(
            SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, _ -> }
        )
        machine.set(State.RUNNING)
        backgroundScope.launch(dispatcher) { machine.asFlow().collect { observed += it } }
        failing.launch {
            machine.asFlow().collect {
                thrower += it
                if (it == State.STOPPING) throw IllegalStateException("subscriber failure")
            }
        }
        observed.clear()

        machine.set(State.STOPPING)
        machine.set(State.RUNNING)
        machine.set(State.STOPPED)

        assertEquals(
            "notification delivery died for good after a subscriber threw: the two transitions " +
                "raised afterwards were never delivered to anyone, so the surviving subscriber " +
                "saw $observed while the stored state had already moved on to ${machine.get()}",
            listOf(State.STOPPING, State.RUNNING, State.STOPPED),
            observed,
        )
        assertEquals(
            "a subscriber that throws is expected to end its own collection there and receive " +
                "nothing further: it saw $thrower",
            listOf(State.RUNNING, State.STOPPING),
            thrower,
        )
        failing.cancel()
    }
}
