package eu.darken.porter.manager.settings

import android.app.Application
import android.content.Context
import android.os.PowerManager
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import eu.darken.porter.manager.NotificationChannels
import eu.darken.porter.manager.PorterSettings
import eu.darken.porter.manager.TestApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The notification step of the start-on-boot and watchdog toggles. Battery optimisation is waived
 * throughout so the earlier step in the same chain never answers for this one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [34])
class SettingsAlertsGuardTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val asked = mutableListOf<String>()
    private var muted = setOf<String>()

    @Before fun setUp() {
        application.createDeviceProtectedStorageContext()
            .getSharedPreferences(PorterSettings.NAME, Context.MODE_PRIVATE).edit().clear().commit()
        PorterSettings.resetForTest()
        PorterSettings.initialize(application)
        val power = application.getSystemService(Context.POWER_SERVICE) as PowerManager
        shadowOf(power).setIgnoringBatteryOptimizations(application.packageName, true)
    }

    private fun model() = SettingsViewModel(
        application,
        SavedStateHandle(),
        alertProbe = { _, channel -> asked += channel; channel !in muted },
        isTelevision = { false },
        rootProbe = { false },
    )

    private fun startOnBoot() = PorterSettings.isStartOnBoot(application)

    @Test fun anUnblockedToggleIsTakenWithoutAsking() {
        val model = model()
        model.toggle(PorterSettings.Keys.KEY_START_ON_BOOT, true)
        assertNull(model.dialog.value)
        assertTrue(startOnBoot())
    }

    @Test fun aMutedStarterChannelStopsTheToggleAndExplains() {
        muted = setOf(NotificationChannels.ADB_START)
        val model = model()
        model.toggle(PorterSettings.Keys.KEY_START_ON_BOOT, true)
        assertEquals("alerts", model.dialog.value)
        assertFalse(startOnBoot())
    }

    @Test fun theUserCanTakeTheSettingWithTheAlertsStillMuted() {
        muted = setOf(NotificationChannels.ADB_START)
        val model = model()
        model.toggle(PorterSettings.Keys.KEY_START_ON_BOOT, true)
        model.applyWithoutAlerts()
        assertTrue(startOnBoot())
        assertNull(model.dialog.value)
        assertNull(model.pendingSetting.value)
    }

    @Test fun returningWithTheAlertsUnmutedTakesTheSetting() {
        muted = setOf(NotificationChannels.ADB_START)
        val model = model()
        model.toggle(PorterSettings.Keys.KEY_START_ON_BOOT, true)
        muted = emptySet()
        model.alertsResult()
        assertTrue(startOnBoot())
        assertNull(model.pendingSetting.value)
    }

    @Test fun returningWithTheAlertsStillMutedDropsTheToggle() {
        muted = setOf(NotificationChannels.ADB_START)
        val model = model()
        model.toggle(PorterSettings.Keys.KEY_START_ON_BOOT, true)
        model.alertsResult()
        assertFalse(startOnBoot())
        assertNull(model.pendingSetting.value)
    }

    /** Nothing may stand between the user and turning a feature back off. */
    @Test fun turningTheSettingOffIsNeverGated() {
        val model = model()
        model.toggle(PorterSettings.Keys.KEY_START_ON_BOOT, true)
        assertTrue(startOnBoot())
        muted = setOf(NotificationChannels.ADB_START)
        model.toggle(PorterSettings.Keys.KEY_START_ON_BOOT, false)
        assertNull(model.dialog.value)
        assertFalse(startOnBoot())
    }

    @Test fun theWatchdogAsksAboutItsOwnChannelAndNotTheCrashOne() {
        val model = model()
        model.toggle(PorterSettings.Keys.KEY_WATCHDOG, true)
        assertEquals(listOf(NotificationChannels.WATCHDOG), asked)
    }

    /** The crash notification offers turning itself off, so muting it is not a misconfiguration. */
    @Test fun aMutedCrashChannelDoesNotStopTheWatchdog() {
        muted = setOf(NotificationChannels.CRASH)
        val model = model()
        model.toggle(PorterSettings.Keys.KEY_WATCHDOG, true)
        assertNull(model.dialog.value)
        assertTrue(PorterSettings.watchdog)
    }

    @Test fun theStarterChannelIsTheOneCheckedForStartOnBoot() {
        val model = model()
        model.toggle(PorterSettings.Keys.KEY_START_ON_BOOT, true)
        assertEquals(listOf(NotificationChannels.ADB_START), asked)
    }
}
