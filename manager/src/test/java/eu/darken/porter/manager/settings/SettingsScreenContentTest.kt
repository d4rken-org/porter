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
class SettingsScreenContentTest : ComposeTest() {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun string(id: Int) = context.getString(id)

    private val clicks = mutableListOf<String>()
    private val actions = SettingsActions(
        onBack = { clicks += "back" },
        onGeneral = { clicks += "general" },
        onStartup = { clicks += "startup" },
        onCompatibility = { clicks += "compatibility" },
        onTerminal = { clicks += "terminal" },
        onAutomation = { clicks += "automation" },
        onDeveloperGuide = { clicks += "developerGuide" },
        onSupport = { clicks += "support" },
        onAcknowledgements = { clicks += "acknowledgements" },
        onVersion = { clicks += "version" },
    )

    private fun state() = SettingsUiState(versionName = "1.2.0-beta3")

    private fun render(state: SettingsUiState = state()) {
        composeTestRule.setContent { PorterTheme(dark = false) { SettingsScreenContent(state, actions) } }
    }

    @Test fun theEntriesAndBothCategoriesRender() {
        render()
        listOf(
            R.string.settings_general, R.string.porter_startup,
            R.string.porter_tools, R.string.compat_setup_title, R.string.home_terminal_title,
            R.string.home_automation_title, R.string.porter_developer_guide,
            R.string.settings_support, R.string.porter_support_title, R.string.porter_acknowledgements,
            R.string.porter_version,
        ).forEach { composeTestRule.onNodeWithText(string(it)).assertIsDisplayed() }
        composeTestRule.onNodeWithText("1.2.0-beta3").assertIsDisplayed()
    }

    @Test fun aStartupEntryWithNothingToFixShowsItsSummary() {
        render()
        composeTestRule.onNodeWithText(string(R.string.settings_startup_summary)).assertIsDisplayed()
        composeTestRule.onAllNodesWithText(string(R.string.settings_startup_attention)).assertCountEquals(0)
    }

    @Test fun aStartupEntryThatNeedsAttentionSaysSo() {
        render(state().copy(startupNeedsAttention = true))
        composeTestRule.onNodeWithText(string(R.string.settings_startup_attention)).assertIsDisplayed()
        composeTestRule.onAllNodesWithText(string(R.string.settings_startup_summary)).assertCountEquals(0)
    }

    @Test fun theGeneralSummaryNamesTheUpdateCheckOnlyWhereItIsSupported() {
        render()
        composeTestRule.onNodeWithText(string(R.string.settings_general_summary_theme)).assertIsDisplayed()
        composeTestRule.onAllNodesWithText(string(R.string.settings_general_summary)).assertCountEquals(0)
    }

    @Test fun aSupportedUpdateCheckIsNamedInTheGeneralSummary() {
        render(state().copy(updateCheckSupported = true))
        composeTestRule.onNodeWithText(string(R.string.settings_general_summary)).assertIsDisplayed()
        composeTestRule.onAllNodesWithText(string(R.string.settings_general_summary_theme)).assertCountEquals(0)
    }

    @Test fun everyRowInvokesItsOwnAction() {
        render()
        listOf(
            R.string.settings_general to "general",
            R.string.porter_startup to "startup",
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
