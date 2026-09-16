package eu.darken.porter.manager.utils

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import eu.darken.porter.manager.utils.PorterStateMachine.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What a subscriber sees when it attaches while a transition is still being handled. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PorterStateMachineLateListenerTest {

    private val machine = PorterStateMachine()
    private val lateArrival = mutableListOf<State>()
    private var acted = false

    @Test fun aListenerIsNotSentTransitionsEnqueuedBeforeItWasRegistered() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        // Stands in for the watchdog: it reacts to RUNNING by driving the service somewhere else
        // and starting a component that subscribes on its own.
        backgroundScope.launch(dispatcher) {
            machine.asFlow().collect { delivered ->
                if (delivered == State.RUNNING && !acted) {
                    acted = true
                    machine.set(State.STOPPING)
                    machine.set(State.STARTING)
                    backgroundScope.launch(dispatcher) { machine.asFlow().collect { lateArrival += it } }
                }
            }
        }

        machine.set(State.RUNNING)

        val observed = lateArrival.toList()
        assertEquals(
            "subscribing hands over the state stored at that moment",
            State.STARTING,
            observed.first(),
        )
        assertFalse(
            "notification delivery went backwards: the subscriber attached while the stored " +
                "state was STARTING and was then delivered STOPPING, a transition raised before " +
                "it subscribed and already superseded. It saw $observed.",
            observed.contains(State.STOPPING),
        )
        assertEquals(
            "the subscriber was replayed transitions from before it subscribed instead of only " +
                "the state it attached on: saw $observed",
            listOf(State.STARTING),
            observed,
        )
    }
}
