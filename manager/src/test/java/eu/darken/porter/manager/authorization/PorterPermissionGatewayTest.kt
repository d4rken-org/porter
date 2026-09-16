package eu.darken.porter.manager.authorization

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import eu.darken.porter.protocol.PorterProtocol
import eu.darken.porter.sdk.Porter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The gateway is where the decision stops being a Bundle: Porter takes the two flags as arguments,
 * so the keys the view model wrote and the arguments the server receives have to stay in step.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PorterPermissionGatewayTest {

    data class Confirmation(val uid: Int, val pid: Int, val code: Int, val allowed: Boolean, val onetime: Boolean)

    private val received = mutableListOf<Confirmation>()

    /** Answers the attach and records the confirmation; both are raw, so nothing is re-encoded. */
    private inner class RecordingServer : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface(PorterProtocol.DESCRIPTOR)
            when (code) {
                IBinder.FIRST_CALL_TRANSACTION + ATTACH -> {
                    reply!!.writeNoException()
                    reply.writeTypedObject(Bundle(), 0)
                }
                IBinder.FIRST_CALL_TRANSACTION + DISPATCH_CONFIRMATION -> {
                    val uid = data.readInt()
                    val pid = data.readInt()
                    val requestCode = data.readInt()
                    val confirmation = data.readTypedObject(Bundle.CREATOR)!!
                    received += Confirmation(uid, pid, requestCode,
                        confirmation.getBoolean(PorterProtocol.PERMISSION_CONFIRMATION_ALLOWED),
                        confirmation.getBoolean(PorterProtocol.PERMISSION_CONFIRMATION_ONETIME))
                }
                else -> return false
            }
            return true
        }
    }

    @Before fun attach() = Porter.onBinderReceived(RecordingServer(), "eu.darken.porter.manager")

    @After fun detach() = Porter.resetForTest()

    private fun reply(allowed: Boolean) = PorterPermissionGateway.dispatch(10123, 4242, 7, Bundle().apply {
        putBoolean(PorterProtocol.PERMISSION_CONFIRMATION_ALLOWED, allowed)
        putBoolean(PorterProtocol.PERMISSION_CONFIRMATION_ONETIME, !allowed)
    })

    @Test fun aPersistentGrantReachesTheServerAsAllowedAndNotOneTime() {
        reply(true)
        assertEquals(listOf(Confirmation(10123, 4242, 7, allowed = true, onetime = false)), received)
    }

    @Test fun aDenialReachesTheServerAsOneTime() {
        reply(false)
        assertEquals(listOf(Confirmation(10123, 4242, 7, allowed = false, onetime = true)), received)
    }

    companion object {
        /** The explicit AIDL ids on IPorterService. */
        private const val ATTACH = 1
        private const val DISPATCH_CONFIRMATION = 15
    }
}
