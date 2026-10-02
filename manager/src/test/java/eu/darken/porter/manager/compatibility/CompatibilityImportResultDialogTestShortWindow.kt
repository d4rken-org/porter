package eu.darken.porter.manager.compatibility

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import eu.darken.porter.manager.ComposeTest
import eu.darken.porter.manager.ui.PorterTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

@Config(qualifiers = "de-w640dp-h240dp-land", fontScale = 2.0f)
class CompatibilityImportResultDialogTestShortWindow : ComposeTest() {
    @Test fun explanationEndCanBeReachedAndDismissedOnceInAShortGermanWindow() {
        val explanation = "Bestehende Porter-Entscheidungen bleiben erhalten und zählen nicht als neu importiert. " +
            "Als übersprungen zählen gespeicherte Importentscheidungen, die nicht mehr angewendet werden konnten, " +
            "etwa weil sich eine App geändert hat oder in Porter bereits eine Entscheidung vorlag. " +
            "Bereits vor dem Import ausgeschlossene Apps werden nicht mitgezählt."
        var state by mutableStateOf(CompatibilityRepository.State(
            status = CompatibilityRepository.Status.INSTALLED, completed = true,
            applied = 1, skipped = 0))
        var dismissals = 0
        composeTestRule.setContent { PorterTheme(dark = false) {
            CompatibilityImportResultDialog(state) {
                dismissals++
                state = state.copy(completed = false, applied = 0, skipped = 0)
            }
        } }
        composeTestRule.onNode(isDialog()).assertIsDisplayed()
        composeTestRule.onNodeWithText("Ausblenden").assertIsDisplayed()
        val content = composeTestRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        val range = content.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("The short window must require scrolling", range.maxValue() > 0f)
        assertEquals(0f, range.value(), 0.5f)
        content.performSemanticsAction(SemanticsActions.ScrollBy) { scroll ->
            assertTrue(scroll(0f, range.maxValue()))
        }
        composeTestRule.waitForIdle()
        val scrolled = content.fetchSemanticsNode()
        val scrolledRange = scrolled.config[SemanticsProperties.VerticalScrollAxisRange]
        assertEquals("Content must reach its bottom", scrolledRange.maxValue(), scrolledRange.value(), 0.5f)

        val text = composeTestRule.onNodeWithText(explanation, useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val layout = layouts.single()
        val lastLine = layout.lineCount - 1
        assertEquals(explanation.length, layout.getLineEnd(lastLine))
        assertFalse("The final line must not be ellipsized", layout.isLineEllipsized(lastLine))
        // Text semantics bounds are clipped by the scroll viewport. Use the unclipped origin and
        // actual final line/glyph geometry, not a partly displayed long Text, to prove the end is visible.
        val origin = text.fetchSemanticsNode().positionInRoot
        val viewport = scrolled.boundsInRoot
        val lineTop = origin.y + layout.getLineTop(lastLine)
        val lineBottom = origin.y + layout.getLineBottom(lastLine)
        val lastGlyph = layout.getBoundingBox(explanation.lastIndex).translate(origin)
        assertTrue("The final line must have visible height", lineBottom > lineTop)
        assertTrue("The final line top must be inside the viewport", lineTop >= viewport.top - 1f)
        assertTrue("The final line bottom must be inside the viewport", lineBottom <= viewport.bottom + 1f)
        assertTrue("The final character must be inside the viewport horizontally",
            lastGlyph.left >= viewport.left - 1f && lastGlyph.right <= viewport.right + 1f)

        composeTestRule.onNodeWithText("Ausblenden").assertIsDisplayed().performClick()
        composeTestRule.onNode(isDialog()).assertDoesNotExist()
        assertEquals(1, dismissals)
    }
}
