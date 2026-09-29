package eu.darken.porter.manager.service

import android.app.Application
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import eu.darken.porter.manager.ServerBinder
import eu.darken.porter.manager.starter.ServiceReplacement
import eu.darken.porter.manager.utils.PorterStateMachine
import eu.darken.porter.protocol.PorterProtocol
import eu.darken.porter.sdk.Porter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServiceStatusRepositoryTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val server = StatusServer()

    @After fun detach() {
        ServiceStatusRepository.resetForTest()
        ServiceReplacement.resetForTest()
        Porter.onBinderReceived(null, "eu.darken.porter.manager")
        ServerBinder.drop(server)
        PorterStateMachine.instance.set(PorterStateMachine.State.STOPPED)
    }

    /** Answers the attach and the calls a status load makes; diagnostics stay unanswered. */
    private class StatusServer : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            val id = code - IBinder.FIRST_CALL_TRANSACTION
            if (id !in ATTACH..GET_SELINUX_CONTEXT) return false
            data.enforceInterface(PorterProtocol.DESCRIPTOR)
            reply!!.writeNoException()
            when (id) {
                ATTACH -> reply.writeTypedObject(Bundle().apply {
                    putInt(PorterProtocol.REPLY_PROTOCOL_VERSION, PorterProtocol.VERSION)
                    putInt(PorterProtocol.REPLY_MIN_PROTOCOL_VERSION, PorterProtocol.MIN_VERSION)
                }, 0)
                GET_UID -> reply.writeInt(2000)
                CHECK_PERMISSION -> reply.writeInt(PackageManager.PERMISSION_GRANTED)
                GET_SELINUX_CONTEXT -> reply.writeString("u:r:shell:s0")
            }
            return true
        }

        companion object {
            /** IPorterService's explicit AIDL ids. */
            private const val ATTACH = 1
            private const val GET_UID = 2
            private const val CHECK_PERMISSION = 3
            private const val GET_SELINUX_CONTEXT = 4
        }
    }

    @Test fun aConnectionPublishedAfterTheServerArrivedUpdatesTheProtocol() = runBlocking {
        ServerBinder.deliver(server)
        PorterStateMachine.instance.set(PorterStateMachine.State.RUNNING)
        val repository = ServiceStatusRepository.get(application)

        // The binder is here and the SDK has not attached yet, as after a service update.
        val early = withTimeout(10_000) { repository.state.first { it.status.uid == 2000 } }
        assertEquals(0, early.status.protocolVersion)

        Porter.onBinderReceived(server, "eu.darken.porter.manager")

        val attached = withTimeout(10_000) {
            repository.state.first { it.status.protocolVersion == PorterProtocol.VERSION }
        }
        assertEquals(2000, attached.status.uid)
    }
}
