package eu.darken.porter.manager.home

import android.content.Context
import androidx.compose.material.icons.twotone.Info
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.test.core.app.ApplicationProvider
import eu.darken.porter.manager.BuildConfig
import eu.darken.porter.manager.ComposeTest
import eu.darken.porter.manager.R
import eu.darken.porter.manager.model.PorterServiceVersion
import eu.darken.porter.manager.model.ServiceStatus
import eu.darken.porter.manager.ui.PorterTheme
import eu.darken.porter.manager.ui.plainText
import eu.darken.porter.manager.utils.PorterStateMachine.State
import org.junit.Assert.assertEquals
import org.junit.Test

class ServiceStatusCardTest : ComposeTest() {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val installed = PorterServiceVersion("1.2.0-beta3", 1200030, "installed-build:debug")
    private val current = ServiceStatus(uid = 2000, protocolVersion = eu.darken.porter.protocol.PorterProtocol.VERSION, permission = true, porterVersion = installed)
    private fun string(id: Int, vararg args: Any) = context.getString(id, *args)
    private val runningTitle get() = plainText(string(R.string.home_status_service_is_running, string(R.string.app_name)))
    private val stoppedTitle get() = plainText(string(R.string.home_status_service_not_running, string(R.string.app_name)))
    private val restartHint get() = string(R.string.porter_service_update_available)

    private var detailClicks = 0
    private fun renderCard(status: ServiceStatus, state: State = State.RUNNING) {
        composeTestRule.setContent {
            PorterTheme { ServiceStatusCard(serviceStatusUi(eu.darken.porter.manager.service.ServiceSnapshot(status, state, installed = installed))) { detailClicks++ } }
        }
    }
    private fun assertNotShown(text: String) = composeTestRule.onAllNodesWithText(text, substring = true).assertCountEquals(0)

    @Test fun stoppedServiceNavigatesToSetup() {
        renderCard(ServiceStatus(), State.STOPPED)
        composeTestRule.onNodeWithText(stoppedTitle).assertIsDisplayed().performClick()
        assertEquals(1, detailClicks)
    }
    @Test fun currentServiceOpensServiceScreenWithoutUpdateAction() {
        renderCard(current)
        composeTestRule.onNodeWithText(runningTitle).performClick()
        assertEquals(1, detailClicks)
        assertNotShown(restartHint)
        assertNotShown(string(R.string.porter_service_update_action))
    }
    @Test fun differentBuildAndRestrictionsCoexistWithoutActions() {
        renderCard(current.copy(permission = false, porterVersion = installed.copy(buildId = "other:debug")))
        composeTestRule.onNodeWithText(restartHint, substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.porter_status_restricted), substring = true).assertIsDisplayed()
        assertNotShown(string(R.string.porter_service_update_action))
    }
    @Test fun missingIdentityOffersUpdateNotice() {
        renderCard(current.copy(porterVersion = null))
        composeTestRule.onNodeWithText(restartHint, substring = true).assertIsDisplayed()
    }
    @Test fun rootServiceIsLabelledRoot() {
        renderCard(current.copy(uid = 0))
        composeTestRule.onNodeWithText(string(R.string.porter_status_running_root), substring = true).assertIsDisplayed()
    }
    @Test fun compatibilityCardOpensItsScreenInBothVariants() {
        var opens = 0
        val description = "Access is waiting for the compatibility app"
        composeTestRule.setContent { PorterTheme { CompatibilityCard("Not installed · Needed by 1 app", description) { opens++ } } }
        composeTestRule.onNodeWithText("Not installed · Needed by 1 app").assertIsDisplayed()
        composeTestRule.onNodeWithText(description).assertIsDisplayed()
        val button = composeTestRule.onNodeWithText(string(R.string.compat_setup_title), substring = true)
        button.assertIsDisplayed().performClick()
        assertEquals(1, opens)
    }

    @Test fun installedCompatibilityCardShowsItsSummaryAndOpensManagement() {
        var opens = 0
        composeTestRule.setContent { PorterTheme { InstalledCompatibilityCard("Installed · Used by 2 apps") { opens++ } } }
        composeTestRule.onNodeWithText("Installed · Used by 2 apps").assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.compat_setup_title), substring = true).performClick()
        assertEquals(1, opens)
        composeTestRule.onNodeWithText(string(R.string.compat_manage)).assertDoesNotExist()
    }

    @Test fun cardBodyTextDefaultsToBodyMedium() {
        var expected = androidx.compose.ui.unit.TextUnit.Unspecified
        composeTestRule.setContent { PorterTheme {
            expected = androidx.compose.material3.MaterialTheme.typography.bodyMedium.fontSize
            HomeCard("Title", androidx.compose.material.icons.Icons.TwoTone.Info) { androidx.compose.material3.Text("Body") }
        } }
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        composeTestRule.onNodeWithText("Body").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!(layouts)
        assertEquals(expected, layouts.single().layoutInput.style.fontSize)
    }

    @Test fun unavailableDiscoveryNeverClaimsZeroCompatibilityUsage() {
        var running by androidx.compose.runtime.mutableStateOf(false)
        var apps by androidx.compose.runtime.mutableStateOf(eu.darken.porter.manager.management.AppsViewModel.State(loading = false))
        composeTestRule.setContent { PorterTheme { androidx.compose.material3.Text(compatibilityUsageText(running, apps)) } }
        composeTestRule.onNodeWithText(string(R.string.compat_usage_stopped)).assertIsDisplayed()
        composeTestRule.runOnIdle { running = true; apps = apps.copy(error = IllegalStateException("Disconnected")) }
        composeTestRule.onNodeWithText(string(R.string.compat_usage_unknown)).assertIsDisplayed()
        composeTestRule.runOnIdle { apps = apps.copy(error = null, failedUsers = listOf(10)) }
        composeTestRule.onNodeWithText(string(R.string.compat_usage_unknown)).assertIsDisplayed()
        composeTestRule.runOnIdle { apps = apps.copy(failedUsers = emptyList()) }
        composeTestRule.onNodeWithText(context.resources.getQuantityString(R.plurals.compat_usage_count, 0, 0)).assertIsDisplayed()
    }

}
