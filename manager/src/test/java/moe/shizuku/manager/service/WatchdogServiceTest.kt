package moe.shizuku.manager.service

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.utils.ShizukuStateMachine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/** How the watchdog reacts to a crash, and the one decision that may turn it off. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WatchdogServiceTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val environment = FakeWatchdogEnvironment()
    private var watchdog: ServiceController<WatchdogService>? = null

    /** Records what the reaction reaches outside the service, and can fail on demand. */
    private class FakeWatchdogEnvironment(private val restartFails: Boolean = false) : WatchdogEnvironment {
        override val replacementRunning = false
        var restarts = 0
            private set

        override fun restartService(context: Context) {
            restarts++
            if (restartFails) throw IllegalStateException("restart failed")
        }
    }

    @Before fun enableWatchdog() {
        ShizukuStateMachine.instance.set(ShizukuStateMachine.State.STOPPED)
        ShizukuSettings.setWatchdogPreference(true)
        WatchdogService.environmentOverride = environment
    }

    @After fun cleanUp() {
        watchdog?.destroy()
        WatchdogService.environmentOverride = null
        ShizukuStateMachine.instance.set(ShizukuStateMachine.State.STOPPED)
    }

    private fun startWatching(): ServiceController<WatchdogService> =
        Robolectric.buildService(WatchdogService::class.java).also { watchdog = it }.create()

    private fun crashNotificationPosted(): Boolean =
        application.getSystemService(NotificationManager::class.java)
            .activeNotifications
            .any { it.notification.channelId == WatchdogService.CRASH_CHANNEL_ID }

    @Test fun theNotificationStopActionTurnsTheWatchdogOff() {
        val controller = Robolectric.buildService(
            WatchdogService::class.java,
            Intent(application, WatchdogService::class.java).setAction("ACTION_STOP_SERVICE"),
        )
        controller.create().startCommand(0, 1)
        assertFalse(ShizukuSettings.getWatchdog())
        controller.destroy()
        assertFalse(ShizukuSettings.getWatchdog())
    }

    @Test fun teardownWithoutTheStopActionKeepsTheWatchdogOn() {
        val controller = Robolectric.buildService(WatchdogService::class.java)
        controller.create()
        controller.destroy()
        assertTrue(ShizukuSettings.getWatchdog())
    }

    @Test fun aCrashReactionIsPostedRatherThanRunInsideTheTransition() {
        startWatching()

        ShizukuStateMachine.instance.set(ShizukuStateMachine.State.CRASHED)

        // Handling the crash inside the transition would hold the state machine's lock across a
        // root shell round trip, stalling every other thread's set() and update().
        assertFalse("the crash was handled inside the transition", crashNotificationPosted())
        assertEquals(0, environment.restarts)

        shadowOf(Looper.getMainLooper()).idle()

        assertTrue("the crash never reached the watchdog", crashNotificationPosted())
        assertEquals(1, environment.restarts)
    }

    @Test fun aCrashSupersededBeforeTheFirstIdleIsStillHandled() {
        startWatching()

        ShizukuStateMachine.instance.set(ShizukuStateMachine.State.CRASHED)
        ShizukuStateMachine.instance.set(ShizukuStateMachine.State.RUNNING)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(
            "the watchdog was not subscribed yet when the crash was raised, so it only ever saw " +
                "the state that superseded it",
            crashNotificationPosted(),
        )
        assertEquals(1, environment.restarts)
    }

    @Test fun aFailingRestartDoesNotStopLaterCrashesFromReacting() {
        val failing = FakeWatchdogEnvironment(restartFails = true)
        WatchdogService.environmentOverride = failing
        startWatching()

        ShizukuStateMachine.instance.set(ShizukuStateMachine.State.CRASHED)
        shadowOf(Looper.getMainLooper()).idle()
        ShizukuStateMachine.instance.set(ShizukuStateMachine.State.RUNNING)
        ShizukuStateMachine.instance.set(ShizukuStateMachine.State.CRASHED)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            "the reaction's failure ended the watchdog's collection, so the second crash was " +
                "never handled",
            2,
            failing.restarts,
        )
    }
}
