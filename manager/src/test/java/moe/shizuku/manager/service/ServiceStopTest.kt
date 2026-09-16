package moe.shizuku.manager.service

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import eu.darken.porter.protocol.PorterProtocol
import eu.darken.porter.sdk.Porter
import moe.shizuku.manager.utils.ShizukuStateMachine
import moe.shizuku.manager.utils.ShizukuStateMachine.State
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
                reply.writeTypedObject(Bundle(), 0)
                return true
            }
            throw RemoteException("transaction refused")
        }

        companion object {
            /** IPorterService.attach, whose explicit AIDL id is 1. */
            private const val ATTACH = 1
        }
    }

    @Before fun attachBinder() {
        Porter.onBinderReceived(RefusingBinder(), "moe.shizuku.manager")
        ShizukuStateMachine.instance.set(State.RUNNING)
    }

    @After fun detachBinder() {
        Porter.resetForTest()
        ShizukuStateMachine.instance.set(State.STOPPED)
    }

    @Test fun aFailedExitReconcilesTheStateInsteadOfStayingStopping() {
        ServiceStatusRepository.stop()
        assertNotEquals(State.STOPPING, ShizukuStateMachine.instance.get())
        assertEquals(State.RUNNING, ShizukuStateMachine.instance.get())
    }
}
