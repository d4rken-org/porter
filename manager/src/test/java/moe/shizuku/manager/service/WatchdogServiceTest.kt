package moe.shizuku.manager.service

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.utils.ShizukuStateMachine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Only a user decision may turn the watchdog preference off. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WatchdogServiceTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()

    @Before fun enableWatchdog() {
        ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPED)
        ShizukuSettings.setWatchdogPreference(true)
    }

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
}
