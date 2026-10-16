package eu.darken.porter.manager.ui

import android.content.Context
import android.os.Bundle
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import eu.darken.porter.manager.ComposeTest
import eu.darken.porter.manager.R
import eu.darken.porter.manager.home.AccessibilityDialogFragment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@Config(qualifiers = "de-w960dp-h540dp-land-television")
class DialogSurfaceDpadScrollTest : ComposeTest() {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val viewport get() = composeTestRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
    private fun range() = viewport.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]

    private fun render(content: @Composable () -> Unit) {
        RuntimeEnvironment.setFontScale(1.3f)
        composeTestRule.setContent {
            val inputMode = LocalInputModeManager.current
            SideEffect { inputMode.requestInputMode(InputMode.Keyboard) }
            PorterTheme { Box(Modifier.width(560.dp).heightIn(max = 540.dp)) { content() } }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun press(key: Key) {
        composeTestRule.onRoot().performKeyInput { pressKey(key) }
        composeTestRule.waitForIdle()
    }

    private fun renderAccessibilityDialog(step: String) {
        val host = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val fragment = AccessibilityDialogFragment().apply { arguments = Bundle().apply { putString("step", step) } }
        host.supportFragmentManager.beginTransaction().add(android.R.id.content, fragment, "dialog").commitNow()
        render {
            DialogSurface(dpadScroll = fragment.dpadScroll, scrollKey = fragment.scrollKey, actions = { fragment.Actions() }) {
                fragment.Content()
            }
        }
    }

    @Test fun theDisclosureScrollsWithTheDpadAndHandsFocusOnAtEitherEnd() {
        renderAccessibilityDialog("enable")
        assertTrue("The disclosure must overflow for this test to mean anything", range().maxValue() > 0f)

        press(Key.DirectionDown)
        viewport.assertIsFocused()
        repeat(20) { if (range().value() < range().maxValue()) press(Key.DirectionDown) }
        assertEquals(range().maxValue(), range().value(), 0.5f)

        press(Key.DirectionDown)
        composeTestRule.onNodeWithText(context.getString(R.string.dialog_adb_pairing_accessibility_agree)).assertIsFocused()

        press(Key.DirectionUp)
        viewport.assertIsFocused()
        repeat(20) { if (range().value() > 0f) press(Key.DirectionUp) }
        assertEquals(0f, range().value(), 0.5f)
        press(Key.DirectionUp)
        assertEquals(0f, range().value(), 0.5f)
    }

    @Test fun theDisclosureStartsAtTheTopAfterTheRestrictedSettingsStep() {
        renderAccessibilityDialog("permission")
        assertTrue("The restricted-settings step must overflow for this test to mean anything", range().maxValue() > 0f)
        viewport.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, range().maxValue()) }
        composeTestRule.waitForIdle()
        assertTrue(range().value() > 0f)

        composeTestRule.onNodeWithText(context.getString(android.R.string.ok)).performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.dialog_adb_pairing_accessibility_agree)).assertExists()
        assertEquals(0f, range().value(), 0.5f)
    }

    @Test fun aFocusedChildKeepsItsDpadNavigation() {
        render {
            DialogSurface(dpadScroll = true, actions = { TextButton(onClick = {}) { Text("Action") } }) {
                Box(Modifier.size(48.dp).testTag("child").focusable())
                Text("Line\n".repeat(60))
            }
        }
        assertTrue(range().maxValue() > 0f)
        composeTestRule.onNodeWithTag("child").requestFocus()

        press(Key.DirectionDown)
        composeTestRule.onNodeWithText("Action").assertIsFocused()
        assertEquals(0f, range().value(), 0.5f)
    }

    @Test fun withoutTheOptInAnOverflowingViewportNeverTakesFocus() {
        render {
            DialogSurface(actions = { TextButton(onClick = {}) { Text("Action") } }) {
                Box(Modifier.size(48.dp).testTag("child").focusable())
                Text("Line\n".repeat(60))
            }
        }
        assertTrue(range().maxValue() > 0f)

        val config = viewport.fetchSemanticsNode().config
        assertFalse(SemanticsProperties.Focused in config)
        assertFalse(SemanticsActions.RequestFocus in config)
    }
}
