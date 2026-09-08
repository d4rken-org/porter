package moe.shizuku.manager.utils

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.shizuku.manager.utils.ShizukuStateMachine.State
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Binder death and reconnection transitions of the process-wide service state. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShizukuStateMachineTest {
    private val seen = mutableListOf<State>()
    private val listener: (State) -> Unit = { seen += it }

    @Before fun reset() { ShizukuStateMachine.set(State.STOPPED) }
    @After fun detach() { ShizukuStateMachine.removeListener(listener) }

    @Test fun listenersReceiveTheCurrentStateImmediately() {
        ShizukuStateMachine.set(State.RUNNING)
        ShizukuStateMachine.addListener(listener)
        assertEquals(listOf(State.RUNNING), seen)
    }

    @Test fun binderDeathWhileRunningIsACrash() {
        ShizukuStateMachine.set(State.RUNNING)
        ShizukuStateMachine.addListener(listener)
        ShizukuStateMachine.setDead()
        assertEquals(State.CRASHED, ShizukuStateMachine.get())
        assertTrue(ShizukuStateMachine.isDead())
        assertEquals(listOf(State.RUNNING, State.CRASHED), seen)
    }

    @Test fun binderDeathAfterUserStopIsACleanStop() {
        ShizukuStateMachine.set(State.RUNNING)
        ShizukuStateMachine.set(State.STOPPING)
        ShizukuStateMachine.setDead()
        assertEquals(State.STOPPED, ShizukuStateMachine.get())
    }

    @Test fun binderDeathWhileNotRunningChangesNothing() {
        ShizukuStateMachine.set(State.STARTING)
        ShizukuStateMachine.addListener(listener)
        ShizukuStateMachine.setDead()
        assertEquals(State.STARTING, ShizukuStateMachine.get())
        assertEquals(listOf(State.STARTING), seen)
    }

    @Test fun reconnectAfterCrashNotifiesRunningAgain() {
        ShizukuStateMachine.set(State.RUNNING)
        ShizukuStateMachine.setDead()
        ShizukuStateMachine.addListener(listener)
        ShizukuStateMachine.set(State.RUNNING)
        assertTrue(ShizukuStateMachine.isRunning())
        assertEquals(listOf(State.CRASHED, State.RUNNING), seen)
    }

    @Test fun repeatedSameStateDoesNotNotify() {
        ShizukuStateMachine.addListener(listener)
        ShizukuStateMachine.set(State.STOPPED)
        assertEquals(listOf(State.STOPPED), seen)
    }

    @Test fun updateWithoutABinderReportsStopped() {
        ShizukuStateMachine.set(State.RUNNING)
        assertEquals(State.STOPPED, ShizukuStateMachine.update())
        assertFalse(ShizukuStateMachine.isRunning())
    }

    @Test fun flowReplaysCurrentStateAndFollowsTransitions() = runTest {
        ShizukuStateMachine.set(State.RUNNING)
        val states = mutableListOf<State>()
        val collector = launch { ShizukuStateMachine.asFlow().take(3).toList(states) }
        runCurrent()
        ShizukuStateMachine.setDead()
        ShizukuStateMachine.set(State.RUNNING)
        collector.join()
        assertEquals(listOf(State.RUNNING, State.CRASHED, State.RUNNING), states)
        assertEquals(State.RUNNING, ShizukuStateMachine.asFlow().first())
    }
}
