package eu.darken.porter.manager.utils

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import eu.darken.porter.manager.utils.PorterStateMachine.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The state a subscriber is handed when it attaches is delivered by the same stream as every
 * transition, so a transition raised on another thread while that first delivery is still running
 * cannot overtake it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PorterStateMachineRegistrationDeliveryTest {

    private val machine = PorterStateMachine()

    /** States the subscriber finished handling, in completion order. */
    private val applied = Collections.synchronizedList(mutableListOf<State>())

    @Test fun aListenerIsNeverGivenAStateOlderThanOneItHasAlreadyBeenGiven() {
        val firstDeliveryStarted = CountDownLatch(1)
        val finishFirstDelivery = CountDownLatch(1)
        val transitionDelivered = CountDownLatch(1)
        val isFirstDelivery = AtomicBoolean(true)
        val scope = CoroutineScope(Dispatchers.IO)
        // Parks inside its first delivery, standing in for any subscriber whose body does not
        // return instantly (posting to a handler, touching a view, a blocking round trip).
        scope.launch {
            machine.asFlow().collect { delivered ->
                if (isFirstDelivery.compareAndSet(true, false)) {
                    firstDeliveryStarted.countDown()
                    finishFirstDelivery.await(10, TimeUnit.SECONDS)
                    applied += delivered
                } else {
                    applied += delivered
                    transitionDelivered.countDown()
                }
            }
        }
        try {
            // The subscriber is attached and is being handed STOPPED, but that delivery has not
            // completed yet: exactly the window a transition on another thread falls into.
            assertTrue(
                "the state it attached on was never delivered",
                firstDeliveryStarted.await(5, TimeUnit.SECONDS),
            )

            machine.set(State.RUNNING)
            finishFirstDelivery.countDown()

            assertTrue(
                "the transition was never delivered to the subscriber",
                transitionDelivered.await(5, TimeUnit.SECONDS),
            )

            val observed = applied.toList()
            assertEquals(
                "notification delivery went backwards: the subscriber attached while the stored " +
                    "state was STOPPED, was then handed RUNNING, and only afterwards was handed " +
                    "the STOPPED it attached on. It saw $observed.",
                listOf(State.STOPPED, State.RUNNING),
                observed,
            )
            assertEquals(
                "the last state the subscriber was given disagrees with the stored state, and " +
                    "nothing will correct it until some later transition: saw $observed",
                machine.get(),
                observed.last(),
            )
        } finally {
            finishFirstDelivery.countDown()
            scope.cancel()
        }
    }

    @Test fun aListenerThatThrowsOnItsRegistrationStateDoesNotUnwindIntoTheCaller() {
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, _ -> }
        )
        // Unconfined, so the first throws inside the subscribe call and the second inside the store.
        scope.launch { machine.asFlow().collect { throw IllegalStateException("subscriber failure") } }
        scope.launch {
            machine.asFlow().collect { if (it == State.RUNNING) throw IllegalStateException("subscriber failure") }
        }

        machine.set(State.RUNNING)

        assertEquals(State.RUNNING, machine.get())
        scope.cancel()
    }
}
