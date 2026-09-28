package eu.darken.porter.manager.settings

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.RestartAlt
import androidx.compose.material.icons.twotone.Wifi
import androidx.compose.ui.test.*
import androidx.test.core.app.ApplicationProvider
import eu.darken.porter.manager.ComposeTest
import eu.darken.porter.manager.R
import eu.darken.porter.manager.ui.PorterTheme
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.annotation.Config

/** Tall viewport so every row is clickable without scrolling the Column first. */
@Config(qualifiers = "w411dp-h2400dp")
class StartupSettingsContentTest : ComposeTest() {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun string(id: Int) = context.getString(id)

    private val clicks = mutableListOf<String>()
    private val actions = StartupSettingsActions(
        onBack = { clicks += "back" },
        onStartOnBootChange = { clicks += "startOnBoot=$it" },
        onWatchdogChange = { clicks += "watchdog=$it" },
        onAutoUpdateServiceChange = { clicks += "autoUpdate=$it" },
        onPairingMethod = { clicks += "pairing" },
        onTcpPort = { clicks += "tcpPort" },
        onBatteryOptimization = { clicks += "battery" },
        onNotificationSettings = { clicks += "alerts" },
    )

    private fun state() = StartupSettingsUiState(
        startOnBoot = false,
        startOnBootEnabled = true,
        watchdog = false,
        autoUpdateService = false,
        autoUpdateServiceEnabled = true,
        showPairingMethod = true,
        pairingMethodLabel = "Pairing code",
        showTcpPort = true,
        tcpPortLabel = "Default (5555)",
        tcpPortNeedsRestart = false,
    )

    private fun render(state: StartupSettingsUiState = state()) {
        composeTestRule.setContent { PorterTheme(dark = false) { StartupSettingsContent(state, actions) } }
    }

    @Test fun theStartupRowsRender() {
        render()
        listOf(
            R.string.porter_startup, R.string.settings_start_on_boot, R.string.settings_watchdog,
            R.string.porter_service_auto_update, R.string.porter_pairing_method, R.string.settings_tcp_port,
        ).forEach { composeTestRule.onNodeWithText(string(it)).assertIsDisplayed() }
    }

    @Test fun serviceAutoUpdateIsOffByDefaultAndRestrictedToPrimaryUser() {
        render(state().copy(autoUpdateServiceEnabled = false))
        composeTestRule.onNodeWithText(string(R.string.porter_service_auto_update)).assertIsOff().assertIsNotEnabled()
    }

    /**
     * The icon a row draws is not part of the semantics tree and `captureToImage()` does not work
     * under Robolectric, so the branch is pinned where it is decided.
     */
    @Test fun aTcpPortChangeThatNeedsAServiceRestartSwitchesTheRowIcon() {
        assertEquals(Icons.TwoTone.Wifi, tcpPortIcon(false))
        assertEquals(Icons.TwoTone.RestartAlt, tcpPortIcon(true))
        render(state().copy(tcpPortNeedsRestart = true))
        composeTestRule.onNodeWithText(string(R.string.settings_tcp_port)).assertIsDisplayed()
    }

    @Test fun theBatteryAndAlertsRowsStayHiddenUnlessFlagged() {
        render()
        composeTestRule.onAllNodesWithText(string(R.string.porter_background_operation)).assertCountEquals(0)
        composeTestRule.onAllNodesWithText(string(R.string.settings_alerts_blocked_title)).assertCountEquals(0)
    }

    @Test fun aFlaggedBatteryRowShowsAlone() {
        render(state().copy(showBatteryAction = true))
        composeTestRule.onNodeWithText(string(R.string.porter_background_operation)).assertIsDisplayed()
        composeTestRule.onAllNodesWithText(string(R.string.settings_alerts_blocked_title)).assertCountEquals(0)
    }

    @Test fun aFlaggedAlertsRowShowsAlone() {
        render(state().copy(showAlertsAction = true))
        composeTestRule.onNodeWithText(string(R.string.settings_alerts_blocked_title)).assertIsDisplayed()
        composeTestRule.onAllNodesWithText(string(R.string.porter_background_operation)).assertCountEquals(0)
    }

    @Test fun everyRowInvokesItsOwnAction() {
        render(state().copy(showBatteryAction = true, showAlertsAction = true))
        listOf(
            R.string.settings_start_on_boot to "startOnBoot=true",
            R.string.settings_watchdog to "watchdog=true",
            R.string.porter_service_auto_update to "autoUpdate=true",
            R.string.porter_background_operation to "battery",
            R.string.settings_alerts_blocked_title to "alerts",
            R.string.porter_pairing_method to "pairing",
            R.string.settings_tcp_port to "tcpPort",
        ).forEach { (id, expected) ->
            composeTestRule.onNodeWithText(string(id)).performClick()
            assertEquals(listOf(expected), clicks.toList())
            clicks.clear()
        }
        composeTestRule.onNodeWithContentDescription(string(R.string.porter_navigate_back)).performClick()
        assertEquals(listOf("back"), clicks.toList())
    }
}
