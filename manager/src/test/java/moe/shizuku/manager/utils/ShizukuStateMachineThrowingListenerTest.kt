package moe.shizuku.manager.utils

import moe.shizuku.manager.utils.ShizukuStateMachine.State
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** One misbehaving listener must not silence the notifications every other listener depends on. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShizukuStateMachineThrowingListenerTest {

    private val observed = mutableListOf<State>()
    private val observer: (State) -> Unit = { observed += it }
    private val thrower: (State) -> Unit = {
        if (it == State.STOPPING) throw IllegalStateException("listener failure")
    }

    @Before fun reset() {
        ShizukuStateMachine.set(State.STOPPED)
    }

    @After fun detach() {
        ShizukuStateMachine.removeListener(observer)
        ShizukuStateMachine.removeListener(thrower)
    }

    @Test fun aThrowingListenerDoesNotStopDeliveryToTheOthers() {
        ShizukuStateMachine.set(State.RUNNING)
        ShizukuStateMachine.addListener(observer)
        ShizukuStateMachine.addListener(thrower)
        observed.clear()

        runCatching { ShizukuStateMachine.set(State.STOPPING) }

        ShizukuStateMachine.set(State.RUNNING)
        ShizukuStateMachine.set(State.STOPPED)

        assertEquals(
            "notification delivery died for good after a listener threw: the two transitions " +
                "raised afterwards were never delivered to anyone, so the surviving observer " +
                "saw $observed while the stored state had already moved on to " +
                "${ShizukuStateMachine.get()}",
            listOf(State.STOPPING, State.RUNNING, State.STOPPED),
            observed,
        )
    }
}
