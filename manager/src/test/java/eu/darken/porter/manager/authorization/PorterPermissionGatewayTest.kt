package eu.darken.porter.manager.authorization

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import eu.darken.porter.common.AppTransactions
import eu.darken.porter.manager.ServerBinder
import eu.darken.porter.protocol.PorterProtocol
import eu.darken.porter.server.IPorterManager
import eu.darken.porter.server.IPorterRemoteProcess
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The gateway is where the decision reaches the wire: the manager binder is fetched from the
 * server over the app's own code, and the two flags arrive on it as the manager sent them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PorterPermissionGatewayTest {

    data class Confirmation(val uid: Int, val pid: Int, val code: Int, val allowed: Boolean, val onetime: Boolean)

    private val received = mutableListOf<Confirmation>()
    private var managerRequests = 0

    /** Records what the manager confirms; nothing else is reachable through it. */
    private inner class RecordingManager : IPorterManager.Stub() {
        override fun newProcess(cmd: Array<String>?, env: Array<String>?, dir: String?): IPorterRemoteProcess? = null
        override fun exit() {}
        override fun attachUserService(binder: IBinder?, token: String?) {}
        override fun dispatchPermissionConfirmationResult(uid: Int, pid: Int, requestCode: Int, allowed: Boolean, onetime: Boolean) {
            received += Confirmation(uid, pid, requestCode, allowed, onetime)
        }
        override fun getFlagsForUid(uid: Int, mask: Int): Int = 0
        override fun updateFlagsForUid(uid: Int, mask: Int, value: Int) {}
    }

    private val manager = RecordingManager()

    /** Hands out the manager binder on the app's code and answers nothing else. */
    private inner class RecordingServer : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != AppTransactions.GET_MANAGER) return false
            data.enforceInterface(PorterProtocol.DESCRIPTOR)
            managerRequests++
            reply!!.writeNoException()
            reply.writeStrongBinder(manager)
            return true
        }
    }

    private val server = RecordingServer()

    @Before fun deliver() = ServerBinder.deliver(server)

    @After fun drop() = ServerBinder.drop(server)

    private fun reply(allowed: Boolean) = PorterPermissionGateway.dispatch(10123, 4242, 7, allowed = allowed, onetime = !allowed)

    @Test fun aPersistentGrantReachesTheServerAsAllowedAndNotOneTime() {
        reply(true)
        assertEquals(listOf(Confirmation(10123, 4242, 7, allowed = true, onetime = false)), received)
    }

    @Test fun aDenialReachesTheServerAsOneTime() {
        reply(false)
        assertEquals(listOf(Confirmation(10123, 4242, 7, allowed = false, onetime = true)), received)
    }

    /** One raw transaction serves every call on the same server binder. */
    @Test fun theManagerBinderIsFetchedOnce() {
        reply(true)
        reply(false)
        assertEquals(1, managerRequests)
        assertEquals(2, received.size)
    }
}
