package eu.darken.porter.manager.settings

import android.content.Context
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
class GeneralSettingsContentTest : ComposeTest() {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun string(id: Int) = context.getString(id)

    private val clicks = mutableListOf<String>()
    private val actions = GeneralSettingsActions(
        onBack = { clicks += "back" },
        onThemeMode = { clicks += "themeMode" },
        onThemeStyle = { clicks += "themeStyle" },
        onThemeColor = { clicks += "themeColor" },
        onUpdateCheckChange = { clicks += "updateCheck=$it" },
        onUpdateChannel = { clicks += "updateChannel" },
    )

    private fun state(themeColorEnabled: Boolean = true) = GeneralSettingsUiState(
        themeModeLabel = "Follow system",
        themeStyleLabel = "Default",
        themeColorLabel = if (themeColorEnabled) "Blue" else string(R.string.porter_theme_color_system),
        themeColorEnabled = themeColorEnabled,
    )

    private fun render(state: GeneralSettingsUiState = state()) {
        composeTestRule.setContent { PorterTheme(dark = false) { GeneralSettingsContent(state, actions) } }
    }

    @Test fun theThemeRowsRender() {
        render()
        listOf(
            R.string.settings_general, R.string.settings_user_interface,
            R.string.porter_theme_mode, R.string.porter_theme_style, R.string.porter_theme_color,
        ).forEach { composeTestRule.onNodeWithText(string(it)).assertIsDisplayed() }
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

    @Test fun unsupportedUpdateCheckHidesItsRows() {
        render(state().copy(updateCheckSupported = false, updateCheck = true, updateChannelLabel = "Production"))
        composeTestRule.onAllNodesWithText(string(R.string.settings_updates)).assertCountEquals(0)
        composeTestRule.onAllNodesWithText(string(R.string.updater_check)).assertCountEquals(0)
        composeTestRule.onAllNodesWithText(string(R.string.updater_channel)).assertCountEquals(0)
    }

    @Test fun supportedUpdateCheckShowsItsRows() {
        render(state().copy(updateCheckSupported = true, updateCheck = true, updateChannelLabel = "Production"))
        composeTestRule.onNodeWithText(string(R.string.settings_updates)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.updater_check)).assertIsDisplayed().assertIsOn()
        composeTestRule.onNodeWithText(string(R.string.updater_channel)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Production").assertIsDisplayed()
    }

    @Test fun updateCheckSwitchReportsItsChange() {
        render(state().copy(updateCheckSupported = true, updateCheck = false, updateChannelLabel = "Production"))
        composeTestRule.onNodeWithText(string(R.string.updater_check)).assertIsOff().performClick()
        assertEquals(listOf("updateCheck=true"), clicks.toList())
    }

    @Test fun updateChannelIsDisabledWhileChecksAreOff() {
        render(state().copy(updateCheckSupported = true, updateCheck = false, updateChannelLabel = "Beta"))
        composeTestRule.onNodeWithText(string(R.string.updater_channel)).assertIsNotEnabled().performClick()
        composeTestRule.onNodeWithText("Beta").assertIsDisplayed()
        assertEquals(emptyList<String>(), clicks.toList())
    }

    @Test fun updateChannelOpensItsChoiceWhileChecksAreOn() {
        render(state().copy(updateCheckSupported = true, updateCheck = true, updateChannelLabel = "Beta"))
        composeTestRule.onNodeWithText(string(R.string.updater_channel)).assertIsEnabled().performClick()
        assertEquals(listOf("updateChannel"), clicks.toList())
    }

    @Test fun everyRowInvokesItsOwnAction() {
        render(state().copy(updateCheckSupported = true, updateCheck = true, updateChannelLabel = "Production"))
        listOf(
            R.string.porter_theme_mode to "themeMode",
            R.string.porter_theme_style to "themeStyle",
            R.string.porter_theme_color to "themeColor",
            R.string.updater_check to "updateCheck=false",
            R.string.updater_channel to "updateChannel",
        ).forEach { (id, expected) ->
            composeTestRule.onNodeWithText(string(id)).performClick()
            assertEquals(listOf(expected), clicks.toList())
            clicks.clear()
        }
        composeTestRule.onNodeWithContentDescription(string(R.string.porter_navigate_back)).performClick()
        assertEquals(listOf("back"), clicks.toList())
    }
}
