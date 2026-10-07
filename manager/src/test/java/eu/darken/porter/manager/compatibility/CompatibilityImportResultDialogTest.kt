package eu.darken.porter.manager.compatibility

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import eu.darken.porter.manager.ComposeTest
import eu.darken.porter.manager.ui.PorterTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

@Config(qualifiers = "en")
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
        assertResult("Newly imported access choices: 20", "Skipped during import: 2", ENGLISH_EXPLANATION)
        composeTestRule.onNodeWithText("Dismiss").assertIsDisplayed().performClick()
        composeTestRule.onNode(isDialog()).assertDoesNotExist()
        assertEquals(1, dismissals)
    }

    @Test fun newlyImportedChoiceExplainsCountsEvenWhenNothingWasSkipped() {
        composeTestRule.setContent { PorterTheme {
            CompatibilityImportResultDialog(CompatibilityRepository.State(
                status = CompatibilityRepository.Status.INSTALLED, completed = true,
                applied = 1, skipped = 0, phase = null, preview = null)) {}
        } }
        assertResult("Newly imported access choices: 1", "Skipped during import: 0", ENGLISH_EXPLANATION)
    }

    @Test fun skippedOnlyImportStillShowsOrderedCountsAndExplanation() {
        composeTestRule.setContent { PorterTheme {
            CompatibilityImportResultDialog(CompatibilityRepository.State(
                status = CompatibilityRepository.Status.INSTALLED, completed = true,
                applied = 0, skipped = 3)) {}
        } }
        assertResult("Newly imported access choices: 0", "Skipped during import: 3", ENGLISH_EXPLANATION)
    }

    @Test fun installationWithoutImportDoesNotShowADialog() {
        composeTestRule.setContent { PorterTheme {
            CompatibilityImportResultDialog(CompatibilityRepository.State(
                status = CompatibilityRepository.Status.INSTALLED, completed = true,
                applied = 0, skipped = 0)) {}
        } }
        composeTestRule.onNode(isDialog()).assertDoesNotExist()
    }

    @Test fun incompleteStatusPhaseAndPreviewGuardsHideTheResult() {
        val completed = CompatibilityRepository.State(
            status = CompatibilityRepository.Status.INSTALLED, completed = true,
            applied = 1, skipped = 0)
        var state by mutableStateOf(completed)
        composeTestRule.setContent { PorterTheme {
            CompatibilityImportResultDialog(state) {}
        } }
        composeTestRule.onNode(isDialog()).assertIsDisplayed()
        val hiddenStates = listOf(completed.copy(completed = false), completed.copy(preview = emptyList())) +
            CompatibilityRepository.Status.entries.filter { it != CompatibilityRepository.Status.INSTALLED }
                .map { completed.copy(status = it) } +
            CompatibilityRepository.Phase.entries.map { completed.copy(phase = it) }
        for (hidden in hiddenStates) {
            composeTestRule.runOnIdle { state = hidden }
            composeTestRule.onNode(isDialog()).assertDoesNotExist()
        }
        composeTestRule.runOnIdle { state = completed }
        composeTestRule.onNode(isDialog()).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "de")
    fun germanResultUsesTheExactLocalizedCopy() {
        composeTestRule.setContent { PorterTheme {
            CompatibilityImportResultDialog(CompatibilityRepository.State(
                status = CompatibilityRepository.Status.INSTALLED, completed = true,
                applied = 1, skipped = 0)) {}
        } }
        assertResult("Neu importierte Zugriffsentscheidungen: 1", "Beim Import übersprungen: 0",
            "Bestehende Porter-Entscheidungen bleiben erhalten und zählen nicht als neu importiert. " +
                "Als übersprungen zählen gespeicherte Importentscheidungen, die nicht mehr angewendet werden konnten, " +
                "etwa weil sich eine App geändert hat oder in Porter bereits eine Entscheidung vorlag. " +
                "Bereits vor dem Import ausgeschlossene Apps werden nicht mitgezählt.")
    }

    @Test
    @Config(qualifiers = "fr")
    fun frenchResultUsesTheExactLocalizedCopy() {
        composeTestRule.setContent { PorterTheme {
            CompatibilityImportResultDialog(CompatibilityRepository.State(
                status = CompatibilityRepository.Status.INSTALLED, completed = true,
                applied = 1, skipped = 0)) {}
        } }
        assertResult("Choix d'accès nouvellement importés : 1", "Ignorés pendant l'importation : 0",
            "Les choix existants de Porter sont conservés et ne comptent pas comme nouvellement importés. " +
                "Les éléments ignorés sont des choix d'importation enregistrés qui n'ont plus pu être appliqués, " +
                "par exemple parce qu'une application a changé ou que Porter avait déjà un choix. " +
                "Les applications exclues avant l'importation ne sont pas comptées.")
    }

    private fun assertResult(appliedText: String, skippedText: String, explanation: String) {
        composeTestRule.onNode(isDialog()).assertIsDisplayed()
        val applied = composeTestRule.onNodeWithText(appliedText).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val skipped = composeTestRule.onNodeWithText(skippedText).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("The counts must remain on separate, ordered rows", skipped.top >= applied.bottom)
        composeTestRule.onNodeWithText(explanation).performScrollTo().assertIsDisplayed()
    }

    private companion object {
        const val ENGLISH_EXPLANATION = "Existing Porter choices are kept and do not count as newly imported. " +
            "Skipped counts saved import choices that could no longer be applied, for example because an app changed " +
            "or Porter already had a choice. Apps excluded before the import are not counted."
    }
}
