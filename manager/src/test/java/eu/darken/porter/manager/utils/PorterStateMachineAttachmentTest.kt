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
import org.robolectric.shadows.ShadowLooper
import eu.darken.porter.protocol.PorterProtocol
import eu.darken.porter.manager.ServerBinder
import eu.darken.porter.sdk.Porter

/**
 * Following the Porter connection is a step of its own, taken once. The SDK exposes no collector
 * count, and the machine's own transition dedup hides a doubled collector, so only the first half
 * is observable here. A null delivery drops the connection after each test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PorterStateMachineAttachmentTest {

    private val server = AttachingServer()

    @After fun detach() {
        Porter.onBinderReceived(null, "eu.darken.porter.manager")
        ServerBinder.drop(server)
    }

    /** Answers the attach, so the SDK keeps the connection; nothing else is called on this path. */
    private class AttachingServer : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != IBinder.FIRST_CALL_TRANSACTION + ATTACH) return false
            data.enforceInterface(PorterProtocol.DESCRIPTOR)
            reply!!.writeNoException()
            reply.writeTypedObject(versionedReply(), 0)
            return true
        }

        companion object {
            /** IPorterService.attach, whose explicit AIDL id is 1. */
            private const val ATTACH = 1

            /** The least a reply has to say for the SDK to keep the connection. */
            fun versionedReply(): Bundle = Bundle().apply {
                putInt(PorterProtocol.REPLY_PROTOCOL_VERSION, PorterProtocol.VERSION)
                putInt(PorterProtocol.REPLY_MIN_PROTOCOL_VERSION, PorterProtocol.MIN_VERSION)
            }
        }
    }

    @Test fun constructingRegistersNothingWithPorter() {
        ServerBinder.deliver(server)
        Porter.onBinderReceived(server, "eu.darken.porter.manager")

        val machine = PorterStateMachine()

        assertEquals("a machine that is not attached must not follow the connection", State.STOPPED, machine.get())
    }

    @Test fun aConnectionPublishedBeforeAttachIsObservable() = runTest {
        // The StateFlow already holds the connection; the collector runs on the next main-looper
        // turn, as a sticky listener registered off the main thread used to.
        ServerBinder.deliver(server)
        Porter.onBinderReceived(server, "eu.darken.porter.manager")

        val machine = PorterStateMachine()
        machine.attachToPorter()
        ShadowLooper.idleMainLooper()

        assertEquals(State.RUNNING, machine.get())
        assertEquals(State.RUNNING, machine.asFlow().first())
    }
}
