package moe.shizuku.manager.home

import android.content.Context
import androidx.compose.ui.test.*
import androidx.test.core.app.ApplicationProvider
import eu.darken.porter.common.DiscoveredApplication
import moe.shizuku.manager.ComposeTest
import moe.shizuku.manager.R
import moe.shizuku.manager.compatibility.CompatibilityRepository
import moe.shizuku.manager.management.AppsViewModel
import moe.shizuku.manager.ui.PorterTheme
import moe.shizuku.manager.utils.ShizukuStateMachine
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.annotation.Config

/** Tall viewport so every card of a state composes; the LazyColumn would drop the ones below the fold. */
@Config(qualifiers = "w411dp-h2000dp")
class HomeScreenContentTest : ComposeTest() {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

    private val companionApp = AppsViewModel.App("example.app", 10123, "Example app", null,
        DiscoveredApplication.ALLOWED, DiscoveredApplication.NEEDS_COMPANION,
        DiscoveredApplication.API_SHIZUKU, false, null)

    private fun statusUi(running: Boolean) = ServiceStatusUi(
        running = running, restricted = false, updateAvailable = false,
        title = if (running) "Porter is running" else "Porter is not running",
        subtitle = null, details = "",
    )

    private fun state(
        running: Boolean = true,
        permitted: Boolean = true,
        apps: List<AppsViewModel.App> = emptyList(),
    ) = HomeUiState(
        statusUi = statusUi(running),
        appsState = AppsViewModel.State(apps, loading = false, accessEnabled = true),
        compatState = CompatibilityRepository.State(),
        buildBadge = null,
        showBatteryCard = false,
        canStart = !running, wirelessAdbAvailable = true, tlsSupported = true,
    )

    private fun actions(onOpenSettings: () -> Unit = {}, onOpenApps: () -> Unit = {}) =
        HomeActions(onOpenSettings, onOpenApps, {}, {}, {}, {}, {}, {}, {})

    private fun render(state: HomeUiState, actions: HomeActions = actions()) {
        composeTestRule.setContent { PorterTheme(dark = false) { HomeScreenContent(state, actions) } }
    }

    private fun assertNotShown(text: String) =
        composeTestRule.onAllNodesWithText(text, substring = true).assertCountEquals(0)

    @Test fun applicationsCardNeedsBothARunningServiceAndPermission() {
        render(state(running = true, permitted = true))
        composeTestRule.onNodeWithText(string(R.string.porter_applications)).assertIsDisplayed()
    }

    @Test fun restrictedAdbKeepsApplicationsNavigation() {
        render(state(running = true, permitted = false))
        composeTestRule.onNodeWithText(string(R.string.porter_applications)).assertIsDisplayed()
    }

    @Test fun stoppedServiceKeepsApplicationsNavigation() {
        render(state(running = false))
        composeTestRule.onNodeWithText(string(R.string.porter_applications)).assertIsDisplayed()
    }

    @Test fun stoppedHomeOffersStartupMethods() {
        render(state(running = false))
        composeTestRule.onNodeWithText(string(R.string.home_wireless_adb_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.home_adb_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.home_root_title)).assertIsDisplayed()
    }

    @Test fun startingServiceHidesStartupMethods() {
        render(state(running = false).copy(canStart = false, busy = true))
        assertNotShown(string(R.string.home_wireless_adb_title))
        assertNotShown(string(R.string.home_root_title))
    }

    @Test fun stoppedSecondaryUserSeesGuidanceInsteadOfStartup() {
        render(state(running = false).copy(canStart = false, primaryUser = false))
        composeTestRule.onNodeWithText(string(R.string.porter_primary_user_title)).assertIsDisplayed()
        assertNotShown(string(R.string.home_wireless_adb_title))
        assertNotShown(string(R.string.home_adb_title))
        assertNotShown(string(R.string.home_root_title))
    }

    @Test fun startupActionsCallTheDashboardHandlers() {
        var pairs = 0
        var starts = 0
        var commands = 0
        render(state(running = false), actions().copy(onPairWireless = { pairs++ },
            onStartWireless = { starts++ }, onShowAdbCommand = { commands++ }))
        composeTestRule.onNodeWithText(string(R.string.adb_pairing)).performClick()
        composeTestRule.onAllNodesWithText(string(R.string.home_root_button_start))[0].performClick()
        composeTestRule.onNodeWithText(string(R.string.home_adb_button_view_command)).performClick()
        assertEquals(1, pairs)
        assertEquals(1, starts)
        assertEquals(1, commands)
    }

    @Test fun runningServiceHidesTheSetupCards() {
        render(state(running = true))
        assertNotShown(string(R.string.home_wireless_adb_title))
        assertNotShown(string(R.string.home_adb_title))
    }

    @Test fun grantedAppsWaitingOnTheCompanionRaiseTheCompatibilityCard() {
        render(state(running = true, permitted = true, apps = listOf(companionApp)))
        composeTestRule.onNodeWithText(string(R.string.compat_card_description)).assertIsDisplayed()
    }

    @Test fun noAppWaitingOnTheCompanionLeavesTheCompatibilityCardOff() {
        render(state(running = true, permitted = true))
        assertNotShown(string(R.string.compat_card_description))
    }

    @Test fun unavailableCountsNeverLookLikeZeroApps() {
        render(state().copy(appsState = AppsViewModel.State(loading = true)))
        composeTestRule.onNodeWithText(string(R.string.porter_apps_counts_unavailable), substring = true).assertIsDisplayed()
        assertNotShown(string(R.string.porter_apps_counts, 0, 0))
    }

    @Test fun topBarActionOpensSettingsAndTheApplicationsCardOpensApps() {
        var settings = 0
        var apps = 0
        render(state(running = true, permitted = true), actions({ settings++ }, { apps++ }))
        composeTestRule.onNodeWithContentDescription(string(R.string.settings_title)).performClick()
        assertEquals(1, settings)
        assertEquals(0, apps)
        composeTestRule.onNodeWithText(string(R.string.porter_applications)).performClick()
        assertEquals(1, apps)
        assertEquals(1, settings)
    }
}
