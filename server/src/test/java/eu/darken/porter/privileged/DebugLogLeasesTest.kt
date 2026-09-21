package eu.darken.porter.privileged

import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.RemoteException
import android.util.Log
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import rikka.shizuku.server.util.Logger
import java.io.FileDescriptor
import java.time.Duration

/**
 * The gate must close on its own and must survive two managers recording at once, so these exercise
 * the deadline and the lease bookkeeping rather than the logging itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class DebugLogLeasesTest {

    /** A remote-like token: a real local Binder's linkToDeath is a no-op, so death is unobservable. */
    internal open class FakeToken : IBinder {
        val recipients = ArrayList<IBinder.DeathRecipient>()

        @Throws(RemoteException::class)
        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {
            recipients.add(recipient)
        }

        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean = recipients.remove(recipient)

        fun die() {
            for (recipient in ArrayList(recipients)) recipient.binderDied()
        }

        override fun getInterfaceDescriptor(): String = "fake"
        override fun pingBinder(): Boolean = true
        override fun isBinderAlive(): Boolean = true
        override fun queryLocalInterface(descriptor: String): IInterface? = null
        override fun dump(fd: FileDescriptor, args: Array<String>?) {}
        override fun dumpAsync(fd: FileDescriptor, args: Array<String>?) {}
        override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = false
    }

    private lateinit var leases: DebugLogLeases

    @Before
    fun closeTheGate() {
        Logger.setDebugUntil(0L)
        Logger.setDebugAlways(false)
        leases = DebugLogLeases()
    }

    @Test
    fun aGrantOpensTheGateAndTheCeilingBoundsIt() {
        assertEquals(DebugLogLeases.MAX_LEASE_MS, leases.update(FakeToken(), 5 * DebugLogLeases.MAX_LEASE_MS))
        assertTrue(Logger.debugEnabled())
    }

    @Test
    fun theGateClosesWhenTheGrantRunsOutWithNothingElseHappening() {
        leases.update(FakeToken(), 10 * MINUTE)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(9))
        assertTrue(Logger.debugEnabled())
        ShadowSystemClock.advanceBy(Duration.ofMinutes(2))
        assertFalse(Logger.debugEnabled())
    }

    @Test
    fun oneManagerStoppingLeavesTheOtherRecordingCovered() {
        val first = FakeToken()
        val second = FakeToken()
        leases.update(first, 5 * MINUTE)
        leases.update(second, 30 * MINUTE)
        leases.update(first, 0)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(20))
        assertTrue(Logger.debugEnabled())
        assertEquals(1, leases.size())
    }

    @Test
    fun releasingTheLastLeaseClosesTheGate() {
        val token = FakeToken()
        leases.update(token, 10 * MINUTE)
        assertEquals(0, leases.update(token, 0))
        assertFalse(Logger.debugEnabled())
        assertEquals(0, leases.size())
    }

    @Test
    fun aDeadManagerReleasesItsOwnLease() {
        val token = FakeToken()
        leases.update(token, 10 * MINUTE)
        token.die()
        assertFalse(Logger.debugEnabled())
        assertEquals(0, leases.size())
    }

    @Test
    fun aReplacedLeaseDyingLateDoesNotRevokeTheLiveOne() {
        val token = FakeToken()
        leases.update(token, 5 * MINUTE)
        val stale = token.recipients[0]
        leases.update(token, 30 * MINUTE)
        stale.binderDied()
        ShadowSystemClock.advanceBy(Duration.ofMinutes(20))
        assertTrue(Logger.debugEnabled())
    }

    @Test
    fun anExpiredLeaseIsPrunedAndUnregistered() {
        val expiring = FakeToken()
        leases.update(expiring, 5 * MINUTE)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(6))
        leases.update(FakeToken(), 5 * MINUTE)
        assertEquals(1, leases.size())
        // Forgetting the entry without unlinking would leak a registration per expiry, and drop()
        // could never clean it up afterwards because the entry is already gone.
        assertTrue(expiring.recipients.isEmpty())
    }

    @Test
    fun aNullTokenGrantsNothing() {
        assertEquals(0, leases.update(null, 10 * MINUTE))
        assertFalse(Logger.debugEnabled())
    }

    @Test
    fun aManagerThatDiedBeforeRegistrationGrantsNothing() {
        val dead = object : FakeToken() {
            @Throws(RemoteException::class)
            override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {
                throw RemoteException("already gone")
            }
        }
        assertEquals(0, leases.update(dead, 10 * MINUTE))
        assertFalse(Logger.debugEnabled())
        assertEquals(0, leases.size())
    }

    @Test
    fun anAlwaysOnBuildSurvivesALeaseBeingTakenAndDropped() {
        Logger.setDebugAlways(true)
        val token = FakeToken()
        leases.update(token, 10 * MINUTE)
        leases.update(token, 0)
        // The lease republishes the deadline as it comes and goes; a debug build must not lose its
        // logging the first time someone records.
        assertTrue(Logger.debugEnabled())
    }

    @Test
    fun infoAndAboveNeverDependOnTheGate() {
        val logger = Logger("test")
        assertFalse(Logger.debugEnabled())
        assertTrue(logger.isLoggable("test", Log.INFO))
        assertTrue(logger.isLoggable("test", Log.WARN))
        assertTrue(logger.isLoggable("test", Log.ERROR))
        assertFalse(logger.isLoggable("test", Log.DEBUG))
        assertFalse(logger.isLoggable("test", Log.VERBOSE))
    }

    @Test
    fun debugAndVerboseFollowTheGate() {
        val logger = Logger("test")
        leases.update(FakeToken(), 10 * MINUTE)
        assertTrue(logger.isLoggable("test", Log.DEBUG))
        assertTrue(logger.isLoggable("test", Log.VERBOSE))
    }

    private companion object {
        const val MINUTE = 60 * 1000L
    }
}
