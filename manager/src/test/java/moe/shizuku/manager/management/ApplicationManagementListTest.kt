package moe.shizuku.manager.management

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import eu.darken.porter.common.DiscoveredApplication
import moe.shizuku.manager.ComposeTest
import moe.shizuku.manager.ui.PorterTheme
import org.junit.Assert.*
import org.junit.Test

class ApplicationManagementListTest : ComposeTest() {
    private val app = AppsViewModel.App("example.app", 10123, "Example app", null,
        DiscoveredApplication.DEFAULT, DiscoveredApplication.NEEDS_COMPANION,
        DiscoveredApplication.API_SHIZUKU, false, null)

    @Test fun pauseSwitchIsOnOnlyWhenAccessIsPaused() {
        var state by mutableStateOf(AppsViewModel.State(listOf(app), loading = false, accessEnabled = true))
        var requested: Boolean? = null
        composeTestRule.setContent { PorterTheme {
            ApplicationManagementList(state, { requested = it }, { _, _ -> })
        } }
        composeTestRule.onNodeWithText("Pause access", substring = true).assertIsOff().performClick()
        assertEquals(false, requested)
        composeTestRule.runOnIdle { state = state.copy(accessEnabled = false) }
        composeTestRule.onNodeWithText("Pause access", substring = true).assertIsOn().performClick()
        assertEquals(true, requested)
    }

    @Test fun savingAndGrantChangesKeepRowsInPlace() {
        var state by mutableStateOf(AppsViewModel.State(listOf(app), loading = false, accessEnabled = true))
        composeTestRule.setContent { PorterTheme {
            ApplicationManagementList(state, {}, { _, _ -> })
        } }
        val row = composeTestRule.onNodeWithText("Example app", substring = true)
        val before = row.fetchSemanticsNode().boundsInRoot
        composeTestRule.runOnIdle { state = state.copy(pendingAccess = mapOf(app.uid to true)) }
        row.assertIsEnabled()
        assertEquals(before, row.fetchSemanticsNode().boundsInRoot)
        composeTestRule.runOnIdle {
            state = state.copy(pendingAccess = emptyMap(), apps = listOf(app.copy(authorization = DiscoveredApplication.PENDING_COMPANION)))
        }
        assertEquals(before, row.fetchSemanticsNode().boundsInRoot)
        composeTestRule.runOnIdle { state = state.copy(apps = listOf(app.copy(authorization = DiscoveredApplication.DENIED))) }
        assertEquals(before, row.fetchSemanticsNode().boundsInRoot)
    }
    @Test fun accessChoicesAreRepresentedByTheSwitchOnly() {
        var state by mutableStateOf(AppsViewModel.State(listOf(app), loading = false, accessEnabled = true))
        composeTestRule.setContent { PorterTheme { ApplicationManagementList(state, {}, { _, _ -> }) } }
        for (authorization in listOf(DiscoveredApplication.DEFAULT, DiscoveredApplication.ALLOWED, DiscoveredApplication.DENIED)) {
            composeTestRule.runOnIdle { state = state.copy(apps = listOf(app.copy(authorization = authorization))) }
            listOf("Access allowed", "Access not allowed yet", "Blocked").forEach {
                composeTestRule.onAllNodesWithText(it, useUnmergedTree = true).assertCountEquals(0)
            }
            val row = composeTestRule.onNodeWithText("Example app", substring = true)
            if (authorization == DiscoveredApplication.ALLOWED) row.assertIsOn() else row.assertIsOff()
        }
    }

    @Test fun globalPauseDisablesRowsWithoutChangingTheirChoices() {
        val allowed = app.copy(authorization = DiscoveredApplication.ALLOWED)
        var state by mutableStateOf(AppsViewModel.State(listOf(allowed), loading = false, accessEnabled = true))
        var toggles = 0
        composeTestRule.setContent { PorterTheme { ApplicationManagementList(state, {}, { _, _ -> toggles++ }) } }
        val row = composeTestRule.onNodeWithText("Example app", substring = true)
        val bounds = row.fetchSemanticsNode().boundsInRoot
        row.assertIsOn().assertIsEnabled()
        composeTestRule.runOnIdle { state = state.copy(pendingGlobalAccess = false) }
        row.assertIsOn().assertIsNotEnabled().performClick()
        assertEquals(0, toggles)
        assertEquals(bounds, row.fetchSemanticsNode().boundsInRoot)
        composeTestRule.onNodeWithText("Pause access", substring = true).assertIsEnabled().assertIsOn()
        composeTestRule.runOnIdle { state = state.copy(pendingGlobalAccess = null, accessEnabled = false) }
        row.assertIsOn().assertIsNotEnabled()
        composeTestRule.runOnIdle { state = state.copy(pendingGlobalAccess = true) }
        row.assertIsOn().assertIsEnabled().performClick()
        assertEquals(1, toggles)
    }
}
