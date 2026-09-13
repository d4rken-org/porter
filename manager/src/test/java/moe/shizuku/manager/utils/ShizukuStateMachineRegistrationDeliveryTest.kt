package moe.shizuku.manager.utils

import moe.shizuku.manager.utils.ShizukuStateMachine.State
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The state handed over at registration is delivered by the registering thread instead of by the
 * drainer, so it is neither ordered against queued transitions nor covered by the drainer's
 * per-listener try/catch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShizukuStateMachineRegistrationDeliveryTest {

    /** States the listener finished handling, in completion order. */
    private val applied = Collections.synchronizedList(mutableListOf<State>())

    private val registrationStarted = CountDownLatch(1)
    private val finishRegistration = CountDownLatch(1)
    private val transitionDelivered = CountDownLatch(1)
    private val isFirstDelivery = AtomicBoolean(true)

    /**
     * Parks inside its registration delivery, standing in for any listener whose body does not
     * return instantly (posting to a handler, touching a view, `trySend` on a full channel).
     */
    private val probe: (State) -> Unit = { delivered ->
        if (isFirstDelivery.compareAndSet(true, false)) {
            registrationStarted.countDown()
            finishRegistration.await(5, TimeUnit.SECONDS)
            applied += delivered
        } else {
            applied += delivered
            transitionDelivered.countDown()
        }
    }

    private val throwsOnFirstDelivery: (State) -> Unit = {
        throw IllegalStateException("listener failure")
    }

    private var registrar: Thread? = null

    @Before fun reset() {
        ShizukuStateMachine.set(State.STOPPED)
    }

    @After fun detach() {
        finishRegistration.countDown()
        registrar?.join(5_000)
        ShizukuStateMachine.removeListener(probe)
        ShizukuStateMachine.removeListener(throwsOnFirstDelivery)
    }

    @Test fun aListenerIsNeverGivenAStateOlderThanOneItHasAlreadyBeenGiven() {
        val thread = Thread { ShizukuStateMachine.addListener(probe) }
        registrar = thread
        thread.start()

        // The listener is in the registry and is being handed STOPPED, but that delivery has not
        // completed yet: exactly the window a transition on another thread falls into.
        assertTrue(
            "registration delivery never started",
            registrationStarted.await(5, TimeUnit.SECONDS),
        )

        ShizukuStateMachine.set(State.RUNNING)
        finishRegistration.countDown()

        assertTrue(
            "the transition was never delivered to the listener",
            transitionDelivered.await(5, TimeUnit.SECONDS),
        )
        thread.join(5_000)

        val observed = applied.toList()
        assertEquals(
            "notification delivery went backwards: the listener registered while the stored " +
                "state was STOPPED, was then handed RUNNING by the drainer, and only afterwards " +
                "was handed the STOPPED it had registered on. It saw $observed.",
            listOf(State.STOPPED, State.RUNNING),
            observed,
        )
        assertEquals(
            "the last state the listener was given disagrees with the stored state, and nothing " +
                "will correct it until some later transition: saw $observed",
            ShizukuStateMachine.get(),
            observed.last(),
        )
    }

    @Test fun aListenerThatThrowsOnItsRegistrationStateDoesNotUnwindIntoTheCaller() {
        ShizukuStateMachine.addListener(throwsOnFirstDelivery)
    }
}
