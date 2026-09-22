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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
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
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [34])
class SettingsAlertsGuardTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val dispatcher = StandardTestDispatcher()
    private val asked = mutableListOf<String>()
    private var muted = setOf<String>()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        application.createDeviceProtectedStorageContext()
            .getSharedPreferences(PorterSettings.NAME, Context.MODE_PRIVATE).edit().clear().commit()
        PorterSettings.resetForTest()
        PorterSettings.initialize(application)
        val power = application.getSystemService(Context.POWER_SERVICE) as PowerManager
        shadowOf(power).setIgnoringBatteryOptimizations(application.packageName, true)
    }

    @After fun tearDown() = Dispatchers.resetMain()

    /** The toggle's write is a coroutine now, so nothing has happened until the test runs it. */
    private fun settle() = dispatcher.scheduler.advanceUntilIdle()

    private fun model() = SettingsViewModel(
        application,
        SavedStateHandle(),
        alertProbe = { _, channel -> asked += channel; channel !in muted },
        isTelevision = { false },
        io = dispatcher,
        rootProbe = { false },
    )

    private fun startOnBoot() = PorterSettings.isStartOnBoot(application)

    @Test fun anUnblockedToggleIsTakenWithoutAsking() {
        val model = model()
        model.toggle(PorterSettings.Keys.KEY_START_ON_BOOT, true)
        settle()
        assertNull(model.dialog.value)
        assertTrue(startOnBoot())
    }

    @Test fun aMutedStarterChannelStopsTheToggleAndExplains() {
        muted = setOf(NotificationChannels.ADB_START)
        val model = model()
        model.toggle(PorterSettings.Keys.KEY_START_ON_BOOT, true)
        settle()
        assertEquals("alerts", model.dialog.value)
        assertFalse(startOnBoot())
    }

    @Test fun theUserCanTakeTheSettingWithTheAlertsStillMuted() {
        muted = setOf(NotificationChannels.ADB_START)
        val model = model()
        model.toggle(PorterSettings.Keys.KEY_START_ON_BOOT, true)
        settle()
        model.applyWithoutAlerts()
        settle()
        assertTrue(startOnBoot())
        assertNull(model.dialog.value)
        assertNull(model.pendingSetting.value)
    }

    @Test fun returningWithTheAlertsUnmutedTakesTheSetting() {
        muted = setOf(NotificationChannels.ADB_START)
        val model = model()
        model.toggle(PorterSettings.Keys.KEY_START_ON_BOOT, true)
        settle()
        muted = emptySet()
        model.alertsResult()
        settle()
        assertTrue(startOnBoot())
        assertNull(model.pendingSetting.value)
    }

    @Test fun returningWithTheAlertsStillMutedDropsTheToggle() {
        muted = setOf(NotificationChannels.ADB_START)
        val model = model()
        model.toggle(PorterSettings.Keys.KEY_START_ON_BOOT, true)
        settle()
        model.alertsResult()
        settle()
        assertFalse(startOnBoot())
        assertNull(model.pendingSetting.value)
    }

    /** Nothing may stand between the user and turning a feature back off. */
    @Test fun turningTheSettingOffIsNeverGated() {
        val model = model()
        model.toggle(PorterSettings.Keys.KEY_START_ON_BOOT, true)
        settle()
        assertTrue(startOnBoot())
        muted = setOf(NotificationChannels.ADB_START)
        model.toggle(PorterSettings.Keys.KEY_START_ON_BOOT, false)
        settle()
        assertNull(model.dialog.value)
        assertFalse(startOnBoot())
    }

    /** The write reaches disk before it returns, so a second toggle must wait for the first. */
    @Test fun aSecondToggleIsRefusedWhileTheFirstIsStillBeingWritten() {
        val model = model()
        model.toggle(PorterSettings.Keys.KEY_START_ON_BOOT, true)
        assertTrue(model.busy.value)
        model.toggle(PorterSettings.Keys.KEY_WATCHDOG, true)
        settle()
        assertFalse(model.busy.value)
        assertTrue(startOnBoot())
        assertFalse(PorterSettings.watchdog)
    }

    /** The activity is what a rotation destroys, so the continuation cannot live only there. */
    @Test fun theWaitForTheNotificationSettingsSurvivesTheScreen() {
        muted = setOf(NotificationChannels.ADB_START)
        val state = SavedStateHandle()
        val model = SettingsViewModel(
            application, state,
            alertProbe = { _, channel -> asked += channel; channel !in muted },
            isTelevision = { false },
            io = dispatcher,
            rootProbe = { false },
        )
        model.toggle(PorterSettings.Keys.KEY_START_ON_BOOT, true)
        settle()
        model.awaitAlertsChoice()
        assertTrue(model.awaitingAlerts.value)

        // The same saved state, handed to the model a recreated screen would get.
        val restored = SettingsViewModel(
            application, state,
            alertProbe = { _, channel -> channel !in muted },
            isTelevision = { false },
            io = dispatcher,
            rootProbe = { false },
        )
        assertTrue(restored.awaitingAlerts.value)
        assertEquals(PorterSettings.Keys.KEY_START_ON_BOOT, restored.pendingSetting.value)
        muted = emptySet()
        restored.alertsResult()
        settle()
        assertTrue(startOnBoot())
        assertFalse(restored.awaitingAlerts.value)
    }

    @Test fun takingTheToggleClearsTheWaitItWasStartedFrom() {
        muted = setOf(NotificationChannels.ADB_START)
        val model = model()
        model.toggle(PorterSettings.Keys.KEY_START_ON_BOOT, true)
        settle()
        model.awaitAlertsChoice()
        model.applyWithoutAlerts()
        settle()
        assertFalse(model.awaitingAlerts.value)
        assertNull(model.pendingSetting.value)
    }

    /** A dialog opened while a write is in flight belongs to whatever the user did next. */
    @Test fun aWriteFinishingDoesNotCloseADialogItDidNotOpen() {
        val model = model()
        model.toggle(PorterSettings.Keys.KEY_WATCHDOG, true)
        assertTrue(model.busy.value)
        model.show("tcp")
        settle()
        assertEquals("tcp", model.dialog.value)
        assertTrue(PorterSettings.watchdog)
    }

    @Test fun theWatchdogAsksAboutItsOwnChannelAndNotTheCrashOne() {
        val model = model()
        model.toggle(PorterSettings.Keys.KEY_WATCHDOG, true)
        settle()
        assertEquals(listOf(NotificationChannels.WATCHDOG), asked)
    }

    /** The crash notification offers turning itself off, so muting it is not a misconfiguration. */
    @Test fun aMutedCrashChannelDoesNotStopTheWatchdog() {
        muted = setOf(NotificationChannels.CRASH)
        val model = model()
        model.toggle(PorterSettings.Keys.KEY_WATCHDOG, true)
        settle()
        assertNull(model.dialog.value)
        assertTrue(PorterSettings.watchdog)
    }

    @Test fun theStarterChannelIsTheOneCheckedForStartOnBoot() {
        val model = model()
        model.toggle(PorterSettings.Keys.KEY_START_ON_BOOT, true)
        settle()
        assertEquals(listOf(NotificationChannels.ADB_START), asked)
    }
}
