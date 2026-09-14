package moe.shizuku.manager.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.shizuku.manager.utils.ShizukuStateMachine.State
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Binder death and reconnection transitions of the service state, and how they are delivered. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShizukuStateMachineTest {

    private val machine = ShizukuStateMachine()
    private val seen = mutableListOf<State>()

    /** Collects on an unconfined dispatcher, so every [ShizukuStateMachine.set] is observed inline. */
    private fun TestScope.collectStates(into: MutableList<State> = seen) =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            machine.asFlow().collect { into += it }
        }

    private fun awaitTrue(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        fail(message)
    }

    @Test fun listenersReceiveTheCurrentStateImmediately() = runTest {
        machine.set(State.RUNNING)
        collectStates()
        assertEquals(listOf(State.RUNNING), seen)
    }

    @Test fun binderDeathWhileRunningIsACrash() = runTest {
        machine.set(State.RUNNING)
        collectStates()
        machine.setDead()
        assertEquals(State.CRASHED, machine.get())
        assertTrue(machine.isDead())
        assertEquals(listOf(State.RUNNING, State.CRASHED), seen)
    }

    @Test fun binderDeathAfterUserStopIsACleanStop() {
        machine.set(State.RUNNING)
        machine.set(State.STOPPING)
        machine.setDead()
        assertEquals(State.STOPPED, machine.get())
    }

    @Test fun binderDeathWhileNotRunningChangesNothing() = runTest {
        machine.set(State.STARTING)
        collectStates()
        machine.setDead()
        assertEquals(State.STARTING, machine.get())
        assertEquals(listOf(State.STARTING), seen)
    }

    @Test fun reconnectAfterCrashNotifiesRunningAgain() = runTest {
        machine.set(State.RUNNING)
        machine.setDead()
        collectStates()
        machine.set(State.RUNNING)
        assertTrue(machine.isRunning())
        assertEquals(listOf(State.CRASHED, State.RUNNING), seen)
    }

    @Test fun repeatedSameStateDoesNotNotify() = runTest {
        collectStates()
        machine.set(State.STOPPED)
        assertEquals(listOf(State.STOPPED), seen)
    }

    @Test fun updateWithoutABinderReportsStopped() {
        machine.set(State.RUNNING)
        assertEquals(State.STOPPED, machine.update())
        assertFalse(machine.isRunning())
    }

    /** The watchdog starts the service from inside its own collector body. */
    @Test fun aTransitionRaisedInsideAListenerIsDeliveredAfterTheOneThatCausedIt() = runTest {
        machine.set(State.RUNNING)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            machine.asFlow().collect { if (it == State.CRASHED) machine.set(State.STARTING) }
        }
        collectStates()
        seen.clear()
        machine.setDead()
        assertEquals(listOf(State.CRASHED, State.STARTING), seen)
        assertEquals(State.STARTING, machine.get())
    }

    @Test fun transitionsFromSeparateThreadsAreNotifiedInStoreOrder() {
        val observed = Collections.synchronizedList(mutableListOf<State>())
        // Unconfined and outside runTest: the test thread blocks in join(), which is not a yield
        // point, and draining inline as each value is stored keeps the race below buffer overflow.
        val scope = CoroutineScope(Dispatchers.Unconfined)
        scope.launch { machine.asFlow().collect { observed += it } }
        observed.clear()
        val start = CountDownLatch(1)
        val threads = listOf(State.RUNNING, State.STOPPING).map { target ->
            Thread {
                start.await()
                repeat(200) { machine.set(target) }
            }
        }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join() }
        scope.cancel()

        val notified = observed.toList()
        // Every notification is a real change, so none can repeat its predecessor, and the last
        // one has to agree with the stored state.
        assertTrue(notified.isNotEmpty())
        assertTrue(notified.zipWithNext().none { (previous, next) -> previous == next })
        assertEquals(machine.get(), notified.last())
    }

    @Test fun flowReplaysCurrentStateAndFollowsTransitions() = runTest {
        machine.set(State.RUNNING)
        val states = mutableListOf<State>()
        val collector = launch { machine.asFlow().take(3).toList(states) }
        runCurrent()
        machine.setDead()
        machine.set(State.RUNNING)
        collector.join()
        assertEquals(listOf(State.RUNNING, State.CRASHED, State.RUNNING), states)
        assertEquals(State.RUNNING, machine.asFlow().first())
    }

    /** A conflating store would collapse these two into the newest and never deliver STOPPING. */
    @Test fun twoTransitionsStoredWithoutADispatchInBetweenAreBothDelivered() = runTest {
        machine.set(State.RUNNING)
        val states = mutableListOf<State>()
        val collector = launch { machine.asFlow().take(3).toList(states) }
        runCurrent()
        machine.set(State.STOPPING)
        machine.set(State.STOPPED)
        collector.join()
        assertEquals(listOf(State.RUNNING, State.STOPPING, State.STOPPED), states)
    }

    /**
     * A subscriber parked inside its body does not stall the store. This says nothing about which
     * dispatcher any particular consumer uses; it only pins that a subscriber that has to be
     * dispatched is not run inside the transition.
     */
    @Test fun aParkedSubscriberDoesNotHoldUpATransitionOnAnotherThread() {
        val handling = CountDownLatch(1)
        val release = CountDownLatch(1)
        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            machine.asFlow().collect {
                if (it == State.RUNNING) {
                    handling.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
            }
        }
        try {
            machine.set(State.RUNNING)
            assertTrue("the subscriber never started handling RUNNING", handling.await(5, TimeUnit.SECONDS))

            val stored = CountDownLatch(1)
            Thread {
                machine.set(State.STOPPING)
                stored.countDown()
            }.start()

            assertTrue(
                "a transition raised while a subscriber was parked inside its body never " +
                    "completed, so the subscriber's body ran inside the store",
                stored.await(5, TimeUnit.SECONDS),
            )
            assertEquals(State.STOPPING, machine.get())
        } finally {
            release.countDown()
            scope.cancel()
        }
    }

    /**
     * The documented degradation of a shared buffer that drops instead of suspending: a subscriber
     * that falls far enough behind loses its oldest unconsumed values, never the newest, and the
     * store never ends up disagreeing with what a new subscriber is replayed.
     */
    @Test fun aSubscriberTooFarBehindLosesItsOldestValuesAndNotTheNewest() = runTest {
        val observed = Collections.synchronizedList(mutableListOf<State>())
        val subscribed = CountDownLatch(1)
        val handling = CountDownLatch(1)
        val release = CountDownLatch(1)
        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            machine.asFlow().collect {
                subscribed.countDown()
                if (it == State.STARTING) {
                    handling.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
                observed += it
            }
        }
        try {
            assertTrue("the subscriber never attached", subscribed.await(5, TimeUnit.SECONDS))
            machine.set(State.STARTING)
            assertTrue("the subscriber never started handling STARTING", handling.await(5, TimeUnit.SECONDS))

            val stored = mutableListOf<State>()
            // Comfortably more than the 257 values the replay slot and the extra buffer hold.
            repeat(600) { index ->
                val next = if (index % 2 == 0) State.RUNNING else State.STOPPING
                machine.set(next)
                stored += next
            }
            machine.set(State.CRASHED)
            stored += State.CRASHED
            release.countDown()

            awaitTrue("the subscriber was never given the newest state") {
                observed.lastOrNull() == State.CRASHED
            }
            val delivered = observed.toList()
            assertEquals(listOf(State.STOPPED, State.STARTING), delivered.take(2))
            val transitions = delivered.drop(2)
            assertTrue(
                "nothing was dropped, so this no longer exercises an overflowing buffer",
                transitions.size < stored.size,
            )
            assertEquals(
                "the subscriber lost values from the middle or the end of the stream rather than " +
                    "its oldest unconsumed ones",
                stored.takeLast(transitions.size),
                transitions,
            )
            assertEquals(State.CRASHED, machine.get())
            assertEquals(
                "the replay slot disagrees with the stored state, and the no-op dedup means no " +
                    "later transition to CRASHED would repair it",
                State.CRASHED,
                machine.asFlow().first(),
            )
        } finally {
            release.countDown()
            scope.cancel()
        }
    }
}
