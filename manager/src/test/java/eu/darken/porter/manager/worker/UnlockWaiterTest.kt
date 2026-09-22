package eu.darken.porter.manager.worker

import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import eu.darken.porter.manager.TestApplication
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The start worker asks for this wait again on every write of adb_wifi_enabled that lands while the keyguard is up. */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [34])
class UnlockWaiterTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()

    private var unlocks = 0
    private val waiter = UnlockWaiter(application) { unlocks++ }

    private fun registered(): Int = shadowOf(application).registeredReceivers
        .count { it.intentFilter.hasAction(Intent.ACTION_USER_PRESENT) }

    private fun unlock() {
        application.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After fun stopWaiting() {
        waiter.stop()
    }

    @Test fun askingAgainKeepsTheOneRegistration() {
        assertTrue(waiter.start())
        assertFalse(waiter.start())
        assertFalse(waiter.start())
        assertEquals(1, registered())
    }

    /** Both registrations would fire, and only the last one could ever be unregistered. */
    @Test fun askingAgainDoesNotLeaveAReceiverBehind() {
        waiter.start()
        waiter.start()
        assertTrue(waiter.stop())
        assertEquals(0, registered())
    }

    @Test fun theUnlockEndsTheWait() {
        waiter.start()
        unlock()
        assertEquals(1, unlocks)
        assertEquals(0, registered())
    }

    @Test fun aSecondUnlockIsNotASecondHandOff() {
        waiter.start()
        unlock()
        unlock()
        assertEquals(1, unlocks)
    }

    @Test fun aWaitThatWasStoppedDoesNotRunOnUnlock() {
        waiter.start()
        assertTrue(waiter.stop())
        unlock()
        assertEquals(0, unlocks)
    }

    /**
     * The settings callback that asks to wait and the flow cleanup that ends it run on different
     * threads, so a `start` can land after the cleanup that would have unregistered it.
     */
    @Test fun aWaitThatWasStoppedCannotBeStartedAgain() {
        waiter.start()
        waiter.stop()
        assertFalse(waiter.start())
        assertEquals(0, registered())
        unlock()
        assertEquals(0, unlocks)
    }

    @Test fun stoppingBeforeTheFirstWaitAlsoEndsIt() {
        assertFalse(waiter.stop())
        assertFalse(waiter.start())
        assertEquals(0, registered())
    }

    @Test fun stoppingWithoutAWaitIsHarmless() {
        assertFalse(waiter.stop())
        assertEquals(0, registered())
    }
}
