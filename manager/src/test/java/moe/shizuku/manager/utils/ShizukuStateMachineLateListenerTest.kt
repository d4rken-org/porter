package moe.shizuku.manager.utils

import moe.shizuku.manager.utils.ShizukuStateMachine.State
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What a listener sees when it attaches while a delivery frame is still draining the queue. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShizukuStateMachineLateListenerTest {

    private val lateArrival = mutableListOf<State>()
    private val late: (State) -> Unit = { lateArrival += it }

    private var acted = false

    /**
     * Stands in for the watchdog: it reacts to RUNNING by driving the service somewhere else and
     * starting a component that attaches its own listener.
     */
    private val registrar: (State) -> Unit = { delivered ->
        if (delivered == State.RUNNING && !acted) {
            acted = true
            ShizukuStateMachine.set(State.STOPPING)
            ShizukuStateMachine.set(State.STARTING)
            ShizukuStateMachine.addListener(late)
        }
    }

    @Before fun reset() {
        ShizukuStateMachine.set(State.STOPPED)
    }

    @After fun detach() {
        ShizukuStateMachine.removeListener(registrar)
        ShizukuStateMachine.removeListener(late)
    }

    @Test fun aListenerIsNotSentTransitionsEnqueuedBeforeItWasRegistered() {
        ShizukuStateMachine.addListener(registrar)

        ShizukuStateMachine.set(State.RUNNING)

        val observed = lateArrival.toList()
        assertEquals(
            "registration hands over the state stored at that moment",
            State.STARTING,
            observed.first(),
        )
        assertFalse(
            "notification delivery went backwards: the listener registered when the stored " +
                "state was STARTING and was then delivered STOPPING, a transition that was " +
                "enqueued before the listener existed and had already been superseded. " +
                "It saw $observed.",
            observed.contains(State.STOPPING),
        )
        assertEquals(
            "the listener was replayed queued transitions from before it existed instead of " +
                "only seeing its registration state: saw $observed",
            listOf(State.STARTING),
            observed,
        )
    }
}
