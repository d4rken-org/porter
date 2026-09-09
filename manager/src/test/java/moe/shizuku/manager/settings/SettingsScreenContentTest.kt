package moe.shizuku.manager.settings

import android.content.Context
import androidx.compose.ui.test.*
import androidx.test.core.app.ApplicationProvider
import moe.shizuku.manager.ComposeTest
import moe.shizuku.manager.R
import moe.shizuku.manager.ui.PorterTheme
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.annotation.Config

/** Tall viewport so every row is clickable without scrolling the Column first. */
@Config(qualifiers = "w411dp-h2400dp")
class SettingsScreenContentTest : ComposeTest() {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun string(id: Int) = context.getString(id)

    private val clicks = mutableListOf<String>()
    private val actions = SettingsActions(
        onBack = { clicks += "back" },
        onStartOnBootChange = { clicks += "startOnBoot=$it" },
        onWatchdogChange = { clicks += "watchdog=$it" },
        onPairingMethod = { clicks += "pairing" },
        onTcpPort = { clicks += "tcpPort" },
        onThemeMode = { clicks += "themeMode" },
        onThemeStyle = { clicks += "themeStyle" },
        onThemeColor = { clicks += "themeColor" },
        onCompatibility = { clicks += "compatibility" },
        onTerminal = { clicks += "terminal" },
        onAutomation = { clicks += "automation" },
        onDeveloperGuide = { clicks += "developerGuide" },
        onSupport = { clicks += "support" },
        onAcknowledgements = { clicks += "acknowledgements" },
        onVersion = { clicks += "version" },
    )

    private fun state(themeColorEnabled: Boolean = true) = SettingsUiState(
        startOnBoot = false,
        startOnBootEnabled = true,
        watchdog = false,
        showPairingMethod = true,
        pairingMethodLabel = "Pairing code",
        showTcpPort = true,
        tcpPortLabel = "Default (5555)",
        tcpPortNeedsRestart = false,
        themeModeLabel = "Follow system",
        themeStyleLabel = "Default",
        themeColorLabel = if (themeColorEnabled) "Blue" else string(R.string.porter_theme_color_system),
        themeColorEnabled = themeColorEnabled,
        versionName = "1.2.0-beta3",
    )

    private fun render(state: SettingsUiState = state()) {
        composeTestRule.setContent { PorterTheme(dark = false) { SettingsScreenContent(state, actions) } }
    }

    @Test fun allFourCategoriesAndTheirRowsRender() {
        render()
        listOf(
            R.string.porter_startup, R.string.settings_start_on_boot, R.string.settings_watchdog,
            R.string.porter_pairing_method, R.string.settings_tcp_port,
            R.string.settings_user_interface, R.string.porter_theme_mode, R.string.porter_theme_style,
            R.string.porter_theme_color,
            R.string.porter_tools, R.string.compat_setup_title, R.string.home_terminal_title,
            R.string.home_automation_title, R.string.porter_developer_guide,
            R.string.settings_support, R.string.porter_support_title, R.string.porter_acknowledgements,
            R.string.porter_version,
        ).forEach { composeTestRule.onNodeWithText(string(it)).assertIsDisplayed() }
        composeTestRule.onNodeWithText("1.2.0-beta3").assertIsDisplayed()
    }

    /**
     * The icon a row draws is not part of the semantics tree and `captureToImage()` does not work
     * under Robolectric, so the branch is pinned where it is decided.
     */
    @Test fun aTcpPortChangeThatNeedsAServiceRestartSwitchesTheRowIcon() {
        assertEquals(R.drawable.ic_wadb_24, tcpPortIcon(false))
        assertEquals(R.drawable.ic_server_restart, tcpPortIcon(true))
        render(state().copy(tcpPortNeedsRestart = true))
        composeTestRule.onNodeWithText(string(R.string.settings_tcp_port)).assertIsDisplayed()
    }

    @Test fun materialYouDisablesTheThemeColorRow() {
        render(state(themeColorEnabled = false))
        composeTestRule.onNodeWithText(string(R.string.porter_theme_color)).assertIsNotEnabled()
        composeTestRule.onNodeWithText(string(R.string.porter_theme_color_system)).assertIsDisplayed()
    }

    @Test fun aSelectableThemeColorRowIsEnabled() {
        render()
        composeTestRule.onNodeWithText(string(R.string.porter_theme_color)).assertIsEnabled()
    }

    @Test fun everyRowInvokesItsOwnAction() {
        render()
        listOf(
            R.string.settings_start_on_boot to "startOnBoot=true",
            R.string.settings_watchdog to "watchdog=true",
            R.string.porter_pairing_method to "pairing",
            R.string.settings_tcp_port to "tcpPort",
            R.string.porter_theme_mode to "themeMode",
            R.string.porter_theme_style to "themeStyle",
            R.string.porter_theme_color to "themeColor",
            R.string.compat_setup_title to "compatibility",
            R.string.home_terminal_title to "terminal",
            R.string.home_automation_title to "automation",
            R.string.porter_developer_guide to "developerGuide",
            R.string.porter_support_title to "support",
            R.string.porter_acknowledgements to "acknowledgements",
            R.string.porter_version to "version",
        ).forEach { (id, expected) ->
            composeTestRule.onNodeWithText(string(id)).performClick()
            assertEquals(listOf(expected), clicks.toList())
            clicks.clear()
        }
        composeTestRule.onNodeWithContentDescription(string(R.string.porter_navigate_back)).performClick()
        assertEquals(listOf("back"), clicks.toList())
    }
}
