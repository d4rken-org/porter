package eu.darken.porter.manager.settings

import eu.darken.porter.manager.NotificationChannels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupAttentionTest {

    private fun battery(television: Boolean = false, ignoring: Boolean = false, startOnBoot: Boolean = false, watchdog: Boolean = false) =
        StartupAttention.batteryRowNeeded(television, ignoring, startOnBoot, watchdog)

    private val asked = mutableListOf<String>()

    private fun alerts(startOnBoot: Boolean = false, watchdog: Boolean = false, muted: Set<String> = emptySet()) =
        StartupAttention.alertsBlocked(startOnBoot, watchdog) { asked += it; it !in muted }

    @Test fun batteryRestrictionWithNeitherFeatureEnabledNeedsNoWarning() {
        assertFalse(battery())
    }

    @Test fun eitherFeatureUnderBatteryRestrictionNeedsTheWarning() {
        assertTrue(battery(startOnBoot = true))
        assertTrue(battery(watchdog = true))
        assertTrue(battery(startOnBoot = true, watchdog = true))
    }

    @Test fun aTelevisionNeverShowsTheBatteryWarning() {
        assertFalse(battery(television = true, startOnBoot = true, watchdog = true))
    }

    @Test fun ignoredBatteryOptimisationsNeedNoWarning() {
        assertFalse(battery(ignoring = true, startOnBoot = true, watchdog = true))
    }

    @Test fun aMutedStarterChannelBlocksAlertsOnlyWhileStartOnBootIsOn() {
        val muted = setOf(NotificationChannels.ADB_START)
        assertTrue(alerts(startOnBoot = true, muted = muted))
        assertFalse(alerts(watchdog = true, muted = muted))
        assertFalse(alerts(muted = muted))
    }

    @Test fun aMutedWatchdogChannelBlocksAlertsOnlyWhileTheWatchdogIsOn() {
        val muted = setOf(NotificationChannels.WATCHDOG)
        assertTrue(alerts(watchdog = true, muted = muted))
        assertFalse(alerts(startOnBoot = true, muted = muted))
        assertFalse(alerts(muted = muted))
    }

    @Test fun theCrashChannelIsNeverConsulted() {
        assertFalse(alerts(startOnBoot = true, watchdog = true, muted = setOf(NotificationChannels.CRASH)))
        assertEquals(listOf(NotificationChannels.ADB_START, NotificationChannels.WATCHDOG), asked.toList())
    }
}
