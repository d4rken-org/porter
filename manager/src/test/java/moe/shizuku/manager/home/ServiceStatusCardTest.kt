package moe.shizuku.manager.home

import android.content.Context
import androidx.compose.ui.test.*
import androidx.test.core.app.ApplicationProvider
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.ComposeTest
import moe.shizuku.manager.R
import moe.shizuku.manager.model.PorterServiceVersion
import moe.shizuku.manager.model.ServiceStatus
import moe.shizuku.manager.ui.PorterTheme
import moe.shizuku.manager.ui.plainText
import moe.shizuku.manager.utils.ShizukuStateMachine.State
import org.junit.Assert.assertEquals
import org.junit.Test

class ServiceStatusCardTest : ComposeTest() {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val installed = PorterServiceVersion("1.2.0-beta3", 1200030)
    private val current = ServiceStatus(uid = 2000, apiVersion = 13, patchVersion = 6, permission = true, porterVersion = installed)
    private fun string(id: Int, vararg args: Any) = context.getString(id, *args)
    private val runningTitle get() = plainText(string(R.string.home_status_service_is_running, string(R.string.app_name)))
    private val stoppedTitle get() = plainText(string(R.string.home_status_service_not_running, string(R.string.app_name)))
    private val restartHint get() = string(R.string.porter_status_restart_service)

    private var detailClicks = 0
    private fun renderCard(status: ServiceStatus, state: State = State.RUNNING) {
        composeTestRule.setContent {
            PorterTheme { ServiceStatusCard(serviceStatusUi(status, state, installed, latestApi = 13, latestPatch = 6)) { detailClicks++ } }
        }
    }
    private fun assertNotShown(text: String) = composeTestRule.onAllNodesWithText(text, substring = true).assertCountEquals(0)

    @Test fun stoppedServiceShowsNoDetailsAndIsNotClickable() {
        renderCard(ServiceStatus(), State.STOPPED)
        composeTestRule.onNodeWithText(stoppedTitle).assertIsDisplayed().assertHasNoClickAction()
        assertNotShown(string(R.string.porter_status_running_adb))
        assertNotShown(string(R.string.porter_status_details_hint))
    }

    @Test fun serviceWithBinderButStateMachineNotRunningCountsAsStopped() {
        renderCard(current, State.STOPPING)
        composeTestRule.onNodeWithText(stoppedTitle).assertIsDisplayed()
        assertNotShown(string(R.string.porter_status_running_adb))
    }

    @Test fun currentAdbServiceShowsRunningAndOpensDetails() {
        renderCard(current)
        composeTestRule.onNodeWithText(runningTitle).assertIsDisplayed().assertHasClickAction().performClick()
        composeTestRule.onNodeWithText(string(R.string.porter_status_running_adb), substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.porter_status_details_hint), substring = true).assertIsDisplayed()
        assertNotShown(restartHint)
        assertNotShown(string(R.string.porter_status_restricted))
        assertEquals(1, detailClicks)
    }

    @Test fun rootServiceIsLabelledRoot() {
        renderCard(current.copy(uid = 0))
        composeTestRule.onNodeWithText(string(R.string.porter_status_running_root), substring = true).assertIsDisplayed()
        assertNotShown(string(R.string.porter_status_running_adb))
    }

    @Test fun serviceWithoutIdentityAsksForRestart() {
        renderCard(current.copy(porterVersion = null))
        composeTestRule.onNodeWithText(restartHint, substring = true).assertIsDisplayed()
    }

    @Test fun serviceFromAnotherPorterBuildAsksForRestart() {
        renderCard(current.copy(porterVersion = PorterServiceVersion("1.1.0-beta9", 1100090)))
        composeTestRule.onNodeWithText(restartHint, substring = true).assertIsDisplayed()
    }

    @Test fun serviceWithOlderApiPatchAsksForRestart() {
        renderCard(current.copy(patchVersion = 5))
        composeTestRule.onNodeWithText(restartHint, substring = true).assertIsDisplayed()
    }

    @Test fun restrictedAdbShowsTheRestrictionOnTheCard() {
        renderCard(current.copy(permission = false))
        composeTestRule.onNodeWithText(string(R.string.porter_status_restricted), substring = true).assertIsDisplayed()
        assertNotShown(restartHint)
    }

    private var stops = 0
    private var dismissals = 0
    private fun renderDialog(status: ServiceStatus) {
        composeTestRule.setContent {
            PorterTheme { ServiceStatusDialog(serviceStatusUi(status, State.RUNNING, installed, latestApi = 13, latestPatch = 6), { dismissals++ }, { stops++ }) }
        }
    }

    @Test fun dialogListsInstalledAndRunningVersionsAndStopsOnConfirm() {
        renderDialog(current)
        val versions = string(R.string.porter_status_versions, installed.name, installed.name, 13, 6)
        composeTestRule.onNodeWithText(versions, substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.porter_status_stop_message), substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.action_stop)).performClick()
        assertEquals(1, stops)
        assertEquals(0, dismissals)
    }

    @Test fun dialogReportsUnknownServiceVersionAndCancelDoesNotStop() {
        renderDialog(current.copy(porterVersion = null))
        val versions = string(R.string.porter_status_versions, installed.name, string(R.string.porter_status_version_unknown), 13, 6)
        composeTestRule.onNodeWithText(versions, substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText(restartHint, substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(android.R.string.cancel)).performClick()
        assertEquals(0, stops)
        assertEquals(1, dismissals)
    }
    @Test fun compatibilityDownloadActionIsOnlyAvailableInFoss() {
        var downloads = 0
        val description = "Access is waiting for the compatibility app"
        composeTestRule.setContent { PorterTheme { CompatibilityCard(description) { downloads++ } } }
        composeTestRule.onNodeWithText(description).assertIsDisplayed()
        val button = composeTestRule.onNodeWithText(string(R.string.porter_get_compatibility))
        if (BuildConfig.IS_FOSS) {
            button.assertIsDisplayed().performClick()
            assertEquals(1, downloads)
        } else {
            button.assertDoesNotExist()
            assertEquals(0, downloads)
        }
    }
}
