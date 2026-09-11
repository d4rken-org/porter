package moe.shizuku.manager.service

import androidx.compose.ui.test.*
import moe.shizuku.manager.ComposeTest
import moe.shizuku.manager.model.PorterServiceVersion
import moe.shizuku.manager.model.ServiceStatus
import moe.shizuku.manager.ui.PorterTheme
import moe.shizuku.manager.utils.ShizukuStateMachine.State
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.annotation.Config

@Config(qualifiers = "w411dp-h2400dp")
class ServiceScreenTest : ComposeTest() {
    private val installed = PorterServiceVersion("1.2.0", 120000, "aaaaaaaaaaaabbbbbbbb:debug")
    private val running = ServiceSnapshot(ServiceStatus(2000, 13, 6, permission = true,
        porterVersion = installed.copy(buildId = "ccccccccccccdddddddd:debug"), pid = 123), State.RUNNING, installed = installed)
    private var updates = 0
    private val actions get() = ServiceActions({}, { updates++ }, {})
    private fun render(snapshot: ServiceSnapshot) {
        composeTestRule.setContent { PorterTheme { ServiceScreenContent(snapshot, actions) } }
    }
    @Test fun sameVersionDifferentBuildShowsBothIdsAndOneUpdateAction() {
        render(running)
        composeTestRule.onNodeWithText("aaaaaaaaaaaa… (debug)", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("cccccccccccc… (debug)", substring = true).assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Update service").assertCountEquals(1)
        composeTestRule.onNodeWithText("Update service").performClick()
        assertEquals(1, updates)
        composeTestRule.onAllNodesWithText("1.2.0 (120000)", substring = true).assertCountEquals(2)
        composeTestRule.onNodeWithText("Updating briefly disconnects", substring = true).assertDoesNotExist()
    }
    @Test fun missingIdentityIsExplicit() {
        render(running.copy(status = running.status.copy(porterVersion = null)))
        composeTestRule.onNodeWithText("Build identity unavailable").assertIsDisplayed()
    }
    @Test fun failureWithSurvivingServiceOffersOnlyRetry() {
        render(running.copy(failed = true))
        composeTestRule.onNodeWithText("Retry update").assertIsDisplayed()
        composeTestRule.onNodeWithText("Update service").assertDoesNotExist()
        composeTestRule.onNodeWithText("The service is still running", substring = true).assertIsDisplayed()
    }
    @Test fun failedStoppedServiceDoesNotOfferNavigationOrRetry() {
        render(running.copy(status = ServiceStatus(), serviceState = State.STOPPED, failed = true))
        composeTestRule.onNodeWithText("Retry update").assertDoesNotExist()
        composeTestRule.onNodeWithText("Go to dashboard").assertDoesNotExist()
        composeTestRule.onNodeWithText("View command").assertDoesNotExist()
    }
    @Test fun updateProgressDoesNotExposeConflictingOperations() {
        render(running.copy(updating = true))
        composeTestRule.onNodeWithText("Update service").assertDoesNotExist()
        composeTestRule.onNodeWithText("Stop Porter").assertDoesNotExist()
        composeTestRule.onNodeWithText("Start").assertDoesNotExist()
        composeTestRule.onNodeWithText("Go to dashboard").assertDoesNotExist()
        composeTestRule.onNodeWithText("You can leave this screen", substring = true).assertIsDisplayed()
    }
    @Test fun secondaryUserGetsInstructionsAndNoUpdateAction() {
        render(running.copy(primaryUser = false))
        composeTestRule.onNodeWithText("primary Android user", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Update service").assertDoesNotExist()
    }
    @Test fun matchingBuildDoesNotOfferUpdate() {
        render(running.copy(status = running.status.copy(porterVersion = installed)))
        composeTestRule.onNodeWithText("Update service").assertDoesNotExist()
        composeTestRule.onNodeWithText("Everything is working normally.").assertIsDisplayed()
    }
    @Test @Config(qualifiers = "w320dp-h480dp")
    fun operationsAreVisibleOnSmallScreenWithoutScrolling() {
        render(running)
        composeTestRule.onNodeWithText("Update service").assertIsDisplayed()
        composeTestRule.onNodeWithText("Stop Porter").assertIsDisplayed()
    }
    @Test fun stoppedSecondaryUserDoesNotOfferDashboardStartup() {
        render(ServiceSnapshot(primaryUser = false, installed = installed))
        composeTestRule.onNodeWithText("Go to dashboard").assertDoesNotExist()
        composeTestRule.onNodeWithText("Stop Porter").assertDoesNotExist()
        composeTestRule.onNodeWithText("primary Android user", substring = true).assertIsDisplayed()
    }
    @Test fun restrictedMatchingServiceDoesNotClaimEverythingIsNormal() {
        render(running.copy(status = running.status.copy(porterVersion = installed, permission = false)))
        composeTestRule.onNodeWithText("Everything is working normally.").assertDoesNotExist()
    }
    @Test fun updateConfirmationCanBeCancelledWithoutUpdating() {
        var dismissals = 0
        composeTestRule.setContent { PorterTheme { UpdateServiceDialog(false, { dismissals++ }, { updates++ }) } }
        composeTestRule.onNodeWithText("Updating briefly disconnects", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel").performClick()
        assertEquals(0, updates)
        assertEquals(1, dismissals)
    }
    @Test fun retryConfirmationInvokesUpdateOnlyWhenConfirmed() {
        composeTestRule.setContent { PorterTheme { UpdateServiceDialog(true, {}, { updates++ }) } }
        assertEquals(0, updates)
        composeTestRule.onAllNodesWithText("Retry update").filterToOne(hasClickAction()).performClick()
        assertEquals(1, updates)
    }
    @Test fun stopConfirmationCanBeCancelledWithoutStopping() {
        var stops = 0
        var dismissals = 0
        composeTestRule.setContent { PorterTheme { StopServiceDialog(true, { dismissals++ }, { stops++ }) } }
        composeTestRule.onNodeWithText("all Android users", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel").performClick()
        assertEquals(0, stops)
        assertEquals(1, dismissals)
    }
}
