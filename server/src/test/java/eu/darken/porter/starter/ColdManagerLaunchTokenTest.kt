package eu.darken.porter.starter

import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.RemoteException
import eu.darken.porter.common.UserServiceLaunch
import eu.darken.porter.protocol.PorterProtocol
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.FileDescriptor
import java.util.concurrent.atomic.AtomicLong

/**
 * A manager the platform just killed is being restarted by the launch itself, so its provider has no
 * server binder to hand over for the first moments after it comes up. The attach this validation runs
 * ahead of waits 5s for that; the validation has to wait too, or it rejects a live token.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ColdManagerLaunchTokenTest {

    /** Yields null until the manager has warmed up, then the server binder. Each read costs time. */
    private class ColdManager(
        private val binder: IBinder,
        private val readsWhileCold: Int,
        private val clock: AtomicLong,
    ) : ServiceStarter.BinderSource {
        var reads = 0

        override fun read(): IBinder? {
            reads++
            clock.addAndGet(READ_COST)
            return if (reads > readsWhileCold) binder else null
        }
    }

    /** Virtual time: a pause costs only the clock the loop measures its deadline against. */
    private class TestPacer(private val clock: AtomicLong) : ServiceStarter.Pacer {

        override fun now(): Long = clock.get()

        override fun pause(millis: Long) {
            clock.addAndGet(millis)
        }
    }

    /** Answers the launch-token query the way the running server does: this token is live. */
    private class ServerBinder : IBinder {

        override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != UserServiceLaunch.TRANSACTION) return false
            data.setDataPosition(0)
            // Refuses the query the way the endpoint does when the token names another wire.
            data.enforceInterface(PorterProtocol.DESCRIPTOR)
            reply!!.writeNoException()
            reply.writeInt(1)
            reply.setDataPosition(0)
            return true
        }

        override fun getInterfaceDescriptor(): String = PorterProtocol.DESCRIPTOR
        override fun pingBinder(): Boolean = true
        override fun isBinderAlive(): Boolean = true
        override fun queryLocalInterface(descriptor: String): IInterface? = null
        override fun dump(fd: FileDescriptor, args: Array<String>?) {}
        override fun dumpAsync(fd: FileDescriptor, args: Array<String>?) {}

        @Throws(RemoteException::class)
        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {}

        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean = true
    }

    @Test
    fun aTokenIsStillValidatedWhenTheManagerIsStartingUp() {
        val clock = AtomicLong(START)
        val manager = ColdManager(ServerBinder(), 3, clock)

        val live = ServiceStarter.isLaunchTokenLive(TOKEN, manager, TestPacer(clock))

        assertTrue(
            "the manager handed over its binder on read 4, well inside the " + TIMEOUT +
                "ms the attach itself waits, but the starter gave up after " + manager.reads +
                " read(s) and rejected a live token",
            live,
        )
    }

    @Test
    fun aManagerThatNeverWarmsUpIsGivenUpOnAtTheTimeout() {
        val clock = AtomicLong(START)
        val manager = ColdManager(ServerBinder(), Int.MAX_VALUE, clock)

        assertFalse(ServiceStarter.isLaunchTokenLive(TOKEN, manager, TestPacer(clock)))
        assertTrue("gave up without ever reading again, so nothing waits for a cold manager", manager.reads > 1)
        assertTrue(
            "kept reading " + (clock.get() - START) + "ms past a " + TIMEOUT + "ms bound",
            clock.get() - START <= TIMEOUT + READ_COST,
        )
    }

    private companion object {
        const val TOKEN = "e2fd2b5a-launch"
        const val TIMEOUT = 5000L
        const val READ_COST = 250L
        const val START = 10_000L
    }
}
