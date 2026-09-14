package moe.shizuku.manager.utils

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import moe.shizuku.manager.utils.ShizukuStateMachine.State
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import rikka.shizuku.Shizuku

/**
 * Registering the Shizuku callbacks is a step of its own, taken once. Shizuku keeps its listeners
 * in process-wide lists and exposes no count, so these read them back by reflection and restore
 * what they found.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShizukuStateMachineAttachmentTest {

    private val receivedBefore = binderReceivedListeners()
    private val deadBefore = binderDeadListeners()
    private val binderReadyBefore = binderReady()

    @After fun detach() {
        (binderReceivedListeners() - receivedBefore.toSet()).forEach { Shizuku.removeBinderReceivedListener(it) }
        (binderDeadListeners() - deadBefore.toSet()).forEach { Shizuku.removeBinderDeadListener(it) }
        setBinderReady(binderReadyBefore)
    }

    @Test fun constructingRegistersNothingWithShizuku() {
        val machine = ShizukuStateMachine()

        assertEquals(receivedBefore, binderReceivedListeners())
        assertEquals(deadBefore, binderDeadListeners())
        assertEquals(State.STOPPED, machine.get())
    }

    @Test fun attachingTwiceRegistersEachCallbackOnce() {
        val machine = ShizukuStateMachine()

        machine.attachToShizuku()
        machine.attachToShizuku()

        assertEquals(
            "the binder-received callback was registered more than once, so every reconnect " +
                "would be reported twice",
            1,
            (binderReceivedListeners() - receivedBefore.toSet()).size,
        )
        assertEquals(
            "the binder-dead callback was registered more than once, so every death would be " +
                "reported twice",
            1,
            (binderDeadListeners() - deadBefore.toSet()).size,
        )
    }

    @Test fun aStickyBinderReceivedDuringAttachIsObservable() = runTest {
        // Shizuku calls a sticky listener inline while registering it, when the binder is already
        // up and the caller is on the main looper. Robolectric is on the main looper here.
        setBinderReady(true)

        val machine = ShizukuStateMachine()
        machine.attachToShizuku()

        assertEquals(State.RUNNING, machine.get())
        assertEquals(State.RUNNING, machine.asFlow().first())
    }

    private fun binderReceivedListeners(): List<Shizuku.OnBinderReceivedListener> = listeners("RECEIVED_LISTENERS")

    private fun binderDeadListeners(): List<Shizuku.OnBinderDeadListener> = listeners("DEAD_LISTENERS")

    @Suppress("UNCHECKED_CAST")
    private fun <T> listeners(name: String): List<T> {
        val holders = Shizuku::class.java.getDeclaredField(name)
            .apply { isAccessible = true }
            .get(null) as List<Any>
        return synchronized(holders) { holders.toList() }.map { holder ->
            holder.javaClass.getDeclaredField("listener").apply { isAccessible = true }.get(holder) as T
        }
    }

    private fun binderReady(): Boolean = readyField().getBoolean(null)

    private fun setBinderReady(value: Boolean) = readyField().setBoolean(null, value)

    private fun readyField() = Shizuku::class.java.getDeclaredField("binderReady").apply { isAccessible = true }
}
