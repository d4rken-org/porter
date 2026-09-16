package eu.darken.porter.manager.utils

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import eu.darken.porter.manager.utils.PorterStateMachine.State
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import eu.darken.porter.protocol.PorterProtocol
import eu.darken.porter.sdk.Porter

/**
 * Registering the Porter callbacks is a step of its own, taken once. The SDK keeps its listeners
 * in process-wide lists and exposes no count, so these read them back by reflection;
 * [Porter.resetForTest] drops the connection and every listener after each test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PorterStateMachineAttachmentTest {

    private val receivedBefore = binderReceivedListeners()
    private val deadBefore = binderDeadListeners()

    @After fun detach() = Porter.resetForTest()

    /** Answers the attach, so the SDK keeps the connection; nothing else is called on this path. */
    private class AttachingServer : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != IBinder.FIRST_CALL_TRANSACTION + ATTACH) return false
            data.enforceInterface(PorterProtocol.DESCRIPTOR)
            reply!!.writeNoException()
            reply.writeTypedObject(Bundle(), 0)
            return true
        }

        companion object {
            /** IPorterService.attach, whose explicit AIDL id is 1. */
            private const val ATTACH = 1
        }
    }

    @Test fun constructingRegistersNothingWithPorter() {
        val machine = PorterStateMachine()

        assertEquals(receivedBefore, binderReceivedListeners())
        assertEquals(deadBefore, binderDeadListeners())
        assertEquals(State.STOPPED, machine.get())
    }

    @Test fun attachingTwiceRegistersEachCallbackOnce() {
        val machine = PorterStateMachine()

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
        // The SDK calls a sticky listener inline while registering it, when the binder is already
        // up and the caller is on the main looper. Robolectric is on the main looper here.
        Porter.onBinderReceived(AttachingServer(), "eu.darken.porter.manager")

        val machine = PorterStateMachine()
        machine.attachToShizuku()

        assertEquals(State.RUNNING, machine.get())
        assertEquals(State.RUNNING, machine.asFlow().first())
    }

    private fun binderReceivedListeners(): List<Porter.OnBinderReceivedListener> = listeners("RECEIVED_LISTENERS")

    private fun binderDeadListeners(): List<Porter.OnBinderDeadListener> = listeners("DEAD_LISTENERS")

    @Suppress("UNCHECKED_CAST")
    private fun <T> listeners(name: String): List<T> {
        val holders = Porter::class.java.getDeclaredField(name)
            .apply { isAccessible = true }
            .get(null) as List<Any>
        return synchronized(holders) { holders.toList() }.map { holder ->
            holder.javaClass.getDeclaredField("listener").apply { isAccessible = true }.get(holder) as T
        }
    }
}
