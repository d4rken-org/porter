package moe.shizuku.manager.compatibility

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import moe.shizuku.manager.ComposeTest
import moe.shizuku.manager.ui.PorterTheme
import org.junit.Assert.assertEquals
import org.junit.Test

class CompatibilityImportResultDialogTest : ComposeTest() {
    @Test fun resultWaitsForOperationToFinishAndDismissesOnce() {
        var state by mutableStateOf(CompatibilityRepository.State(
            status = CompatibilityRepository.Status.INSTALLED, completed = true,
            applied = 20, skipped = 2, phase = CompatibilityRepository.Phase.ACTIVATING))
        var dismissals = 0
        composeTestRule.setContent { PorterTheme {
            CompatibilityImportResultDialog(state) {
                dismissals++
                state = state.copy(completed = false, applied = 0, skipped = 0)
            }
        } }
        composeTestRule.onNode(isDialog()).assertDoesNotExist()
        composeTestRule.runOnIdle { state = state.copy(phase = null) }
        composeTestRule.onNode(isDialog()).assertIsDisplayed()
        composeTestRule.onNodeWithText("Imported: 20").assertIsDisplayed()
        composeTestRule.onNodeWithText("Skipped: 2").assertIsDisplayed()
        val imported = composeTestRule.onNodeWithText("Imported: 20").fetchSemanticsNode().boundsInRoot
        val skipped = composeTestRule.onNodeWithText("Skipped: 2").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(skipped.top >= imported.bottom)
        composeTestRule.onNodeWithText("Dismiss").performClick()
        composeTestRule.onNode(isDialog()).assertDoesNotExist()
        assertEquals(1, dismissals)
    }

    @Test fun installationWithoutImportDoesNotShowADialog() {
        composeTestRule.setContent { PorterTheme {
            CompatibilityImportResultDialog(CompatibilityRepository.State(
                status = CompatibilityRepository.Status.INSTALLED, completed = true)) {}
        } }
        composeTestRule.onNode(isDialog()).assertDoesNotExist()
    }
}
