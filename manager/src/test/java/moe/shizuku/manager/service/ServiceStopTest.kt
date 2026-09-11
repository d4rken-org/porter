package moe.shizuku.manager.service

import android.os.Binder
import android.os.Parcel
import android.os.RemoteException
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
import rikka.shizuku.Shizuku

/** A stop request that fails must not leave the state machine reporting STOPPING. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServiceStopTest {

    /** Alive as far as pingBinder is concerned, but refuses every transaction. */
    private class RefusingBinder : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
            throw RemoteException("transaction refused")
    }

    @Before fun attachBinder() {
        Shizuku.onBinderReceived(RefusingBinder(), "moe.shizuku.manager")
        ShizukuStateMachine.set(State.RUNNING)
    }

    @After fun detachBinder() {
        Shizuku.onBinderReceived(null, "moe.shizuku.manager")
        ShizukuStateMachine.set(State.STOPPED)
    }

    @Test fun aFailedExitReconcilesTheStateInsteadOfStayingStopping() {
        ServiceStatusRepository.stop()
        assertNotEquals(State.STOPPING, ShizukuStateMachine.get())
        assertEquals(State.RUNNING, ShizukuStateMachine.get())
    }
}
