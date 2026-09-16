package eu.darken.porter.manager.home

import android.content.Context
import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import eu.darken.porter.manager.ComposeTest
import eu.darken.porter.manager.R
import eu.darken.porter.manager.ui.ComposeDialogFragment
import eu.darken.porter.manager.ui.DialogSurface
import eu.darken.porter.manager.ui.PorterTheme
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Every dialog that leads with the long "development settings" label measures it against the full
 * row, so whatever follows gets the remainder. A narrow screen with a doubled font scale is where
 * that remainder runs out.
 */
@Config(qualifiers = "w320dp-h480dp")
class DialogActionsLayoutTest : ComposeTest() {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /**
     * Renders the dialog at the width production gives it, the screen minus 48dp. The fragment is
     * added to a container so it attaches without also opening its own dialog window.
     */
    private fun showDialog(fragment: ComposeDialogFragment) {
        RuntimeEnvironment.setFontScale(2f)
        val host = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        host.supportFragmentManager.beginTransaction()
            .add(android.R.id.content, fragment, "dialog").commitNow()

        composeTestRule.setContent {
            PorterTheme {
                Box(Modifier.width(LocalConfiguration.current.screenWidthDp.dp - 48.dp)) {
                    DialogSurface(actions = { fragment.Actions() }) { fragment.Content() }
                }
            }
        }
    }

    private fun assertDisplayed(vararg ids: Int) = ids.forEach {
        composeTestRule.onNodeWithText(context.getString(it)).assertIsDisplayed()
    }

    @Test fun discoveryDialogKeepsEveryActionOnANarrowDisplayWithLargeText() {
        showDialog(AdbDialogFragment())
        assertDisplayed(R.string.development_settings, android.R.string.cancel)
    }

    @Test fun accessibilityNavigateStepKeepsEveryActionOnANarrowDisplayWithLargeText() {
        showDialog(AccessibilityDialogFragment().apply { arguments = Bundle().apply { putString("step", "navigate") } })
        assertDisplayed(R.string.development_settings, android.R.string.cancel)
    }

    @Test fun usbDebuggingDialogKeepsEveryActionOnANarrowDisplayWithLargeText() {
        showDialog(WadbEnableUsbDebuggingDialogFragment())
        assertDisplayed(R.string.development_settings, android.R.string.cancel)
    }
}
