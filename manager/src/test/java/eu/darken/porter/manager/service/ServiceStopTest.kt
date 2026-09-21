package eu.darken.porter.manager.service

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import eu.darken.porter.protocol.PorterProtocol
import eu.darken.porter.sdk.Porter
import eu.darken.porter.manager.ServerBinder
import eu.darken.porter.manager.utils.PorterStateMachine
import eu.darken.porter.manager.utils.PorterStateMachine.State
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A stop request that fails must not leave the state machine reporting STOPPING. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServiceStopTest {

    /** Attaches, so the SDK keeps the connection, and refuses every operation after that. */
    private class RefusingBinder : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == IBinder.FIRST_CALL_TRANSACTION + ATTACH) {
                data.enforceInterface(PorterProtocol.DESCRIPTOR)
                reply!!.writeNoException()
                reply.writeTypedObject(Bundle().apply {
                    putInt(PorterProtocol.REPLY_PROTOCOL_VERSION, PorterProtocol.VERSION)
                    putInt(PorterProtocol.REPLY_MIN_PROTOCOL_VERSION, PorterProtocol.MIN_VERSION)
                }, 0)
                return true
            }
            throw RemoteException("transaction refused")
        }

        companion object {
            /** IPorterService.attach, whose explicit AIDL id is 1. */
            private const val ATTACH = 1
        }
    }

    private val server = RefusingBinder()

    @Before fun attachBinder() {
        ServerBinder.deliver(server)
        Porter.onBinderReceived(server, "eu.darken.porter.manager")
        PorterStateMachine.instance.set(State.RUNNING)
    }

    @After fun detachBinder() {
        Porter.onBinderReceived(null, "eu.darken.porter.manager")
        ServerBinder.drop(server)
        PorterStateMachine.instance.set(State.STOPPED)
    }

    @Test fun aFailedExitReconcilesTheStateInsteadOfStayingStopping() {
        ServiceStatusRepository.stop()
        assertNotEquals(State.STOPPING, PorterStateMachine.instance.get())
        assertEquals(State.RUNNING, PorterStateMachine.instance.get())
    }
}
