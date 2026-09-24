package eu.darken.porter.manager.home

import android.content.Context
import androidx.compose.ui.test.*
import androidx.test.core.app.ApplicationProvider
import eu.darken.porter.common.DiscoveredApplication
import eu.darken.porter.manager.ComposeTest
import eu.darken.porter.manager.R
import eu.darken.porter.manager.compatibility.CompatibilityRepository
import eu.darken.porter.manager.management.AppsViewModel
import eu.darken.porter.manager.ui.PorterTheme
import eu.darken.porter.manager.utils.PorterStateMachine
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

    private val installedCompanion = CompatibilityRepository.State(status = CompatibilityRepository.Status.INSTALLED,
        isCompanion = true, installedVersionName = "1.1", installedVersionCode = 101010)

    private fun statusUi(running: Boolean) = ServiceStatusUi(
        running = running, restricted = false, updateAvailable = false,
        title = if (running) "Porter is running" else "Porter is not running",
        subtitle = null, details = "",
    )

    private fun state(
        running: Boolean = true,
        permitted: Boolean = true,
        apps: List<AppsViewModel.App> = emptyList(),
        compat: CompatibilityRepository.State = CompatibilityRepository.State(),
    ) = HomeUiState(
        statusUi = statusUi(running),
        appsState = AppsViewModel.State(apps, loading = false, accessEnabled = true),
        compatState = compat,
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

    @Test fun missingCompanionSummarizesHowManyAppsNeedIt() {
        render(state(running = true, apps = listOf(companionApp)))
        composeTestRule.onNodeWithText(string(R.string.compat_summary, string(R.string.compat_summary_missing),
            context.resources.getQuantityString(R.plurals.compat_needed_count, 1, 1))).assertIsDisplayed()
    }

    @Test fun partialDiscoveryLeavesTheNeededCountOut() {
        val partial = state(running = true, apps = listOf(companionApp))
        render(partial.copy(appsState = partial.appsState.copy(failedUsers = listOf(10))))
        composeTestRule.onNodeWithText(string(R.string.compat_summary_missing)).assertIsDisplayed()
        assertNotShown(context.resources.getQuantityString(R.plurals.compat_needed_count, 1, 1))
    }

    @Test fun anotherShizukuAppExplainsTheReplacement() {
        render(state(running = true, apps = listOf(companionApp),
            compat = CompatibilityRepository.State(status = CompatibilityRepository.Status.CONFLICT)))
        composeTestRule.onNodeWithText(string(R.string.compat_summary_conflict), substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.compat_conflict_description)).assertIsDisplayed()
        assertNotShown(string(R.string.compat_card_description))
    }

    @Test fun conflictWithoutIntegratedReplacementSaysWhatTheDetailsScreenSays() {
        val conflict = state(running = true, apps = listOf(companionApp),
            compat = CompatibilityRepository.State(status = CompatibilityRepository.Status.CONFLICT))
        render(conflict.copy(integratedCompatibility = false))
        composeTestRule.onNodeWithText(string(R.string.compat_other_build)).assertIsDisplayed()
        assertNotShown(string(R.string.compat_conflict_description))
    }

    @Test fun conflictOnASecondaryUserPointsToThePrimaryUser() {
        val conflict = state(running = true, apps = listOf(companionApp),
            compat = CompatibilityRepository.State(status = CompatibilityRepository.Status.CONFLICT))
        render(conflict.copy(primaryUser = false))
        composeTestRule.onNodeWithText(string(R.string.compat_primary_user)).assertIsDisplayed()
        assertNotShown(string(R.string.compat_conflict_description))
    }

    @Test fun conflictWithShizukuForAnotherUserExplainsTheLimit() {
        render(state(running = true, apps = listOf(companionApp),
            compat = CompatibilityRepository.State(status = CompatibilityRepository.Status.CONFLICT, otherUsers = true)))
        composeTestRule.onNodeWithText(string(R.string.compat_other_users)).assertIsDisplayed()
        assertNotShown(string(R.string.compat_conflict_description))
    }

    @Test fun installedCompanionFoldsUsageIntoItsSummary() {
        render(state(running = true, apps = listOf(companionApp.copy(connectionStatus = DiscoveredApplication.COMPANION)),
            compat = installedCompanion))
        composeTestRule.onNodeWithText(string(R.string.compat_summary, string(R.string.compat_summary_installed),
            context.resources.getQuantityString(R.plurals.compat_usage_count, 1, 1))).assertIsDisplayed()
        assertNotShown("101010")
    }

    @Test fun installedCompanionWithoutUsageSaysSo() {
        render(state(running = true, compat = installedCompanion))
        composeTestRule.onNodeWithText(string(R.string.compat_summary, string(R.string.compat_summary_installed),
            string(R.string.compat_usage_none))).assertIsDisplayed()
    }

    @Test fun stoppedServiceLeavesOnlyTheInstallState() {
        render(state(running = false, compat = installedCompanion))
        composeTestRule.onNodeWithText(string(R.string.compat_summary_installed)).assertIsDisplayed()
        assertNotShown(string(R.string.compat_usage_stopped))
    }

    @Test fun updateTintsTheInstalledCardAndKeepsUsage() {
        render(state(running = true, compat = installedCompanion.copy(status = CompatibilityRepository.Status.UPDATE)))
        composeTestRule.onNodeWithText(string(R.string.compat_summary, string(R.string.compat_status_update),
            string(R.string.compat_usage_none))).assertIsDisplayed()
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
