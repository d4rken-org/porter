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
        running = running, restricted = false, needsRestart = false,
        title = if (running) "Porter is running" else "Porter is not running",
        subtitle = null, details = "", versionDetails = "",
    )

    private fun state(
        running: Boolean = true,
        permitted: Boolean = true,
        apps: List<AppsViewModel.App> = emptyList(),
    ) = HomeUiState(
        statusUi = statusUi(running),
        permitted = permitted,
        appsState = AppsViewModel.State(apps, loading = false, accessEnabled = true),
        compatState = CompatibilityRepository.State(),
        buildBadge = null,
        showBatteryCard = false,
        isSecondaryUser = false,
        isRooted = false,
        wirelessAdbAvailable = !running,
        tlsSupported = true,
        serviceState = if (running) ShizukuStateMachine.State.RUNNING else ShizukuStateMachine.State.STOPPED,
    )

    private fun actions(onOpenSettings: () -> Unit = {}, onOpenApps: () -> Unit = {}) =
        HomeActions(onOpenSettings, onOpenApps, {}, {}, {}, {}, {}, {}, {}, {})

    private fun render(state: HomeUiState, actions: HomeActions = actions()) {
        composeTestRule.setContent { PorterTheme(dark = false) { HomeScreenContent(state, actions) } }
    }

    private fun assertNotShown(text: String) =
        composeTestRule.onAllNodesWithText(text, substring = true).assertCountEquals(0)

    @Test fun applicationsCardNeedsBothARunningServiceAndPermission() {
        render(state(running = true, permitted = true))
        composeTestRule.onNodeWithText(string(R.string.porter_applications)).assertIsDisplayed()
    }

    @Test fun restrictedAdbHidesTheApplicationsCard() {
        render(state(running = true, permitted = false))
        assertNotShown(string(R.string.porter_applications))
    }

    @Test fun stoppedServiceHidesTheApplicationsCard() {
        render(state(running = false))
        assertNotShown(string(R.string.porter_applications))
    }

    @Test fun setupCardsAppearOnlyWhileTheServiceIsStopped() {
        render(state(running = false))
        composeTestRule.onNodeWithText(string(R.string.home_wireless_adb_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.home_adb_title)).assertIsDisplayed()
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
