package eu.darken.porter.manager.home

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import eu.darken.porter.manager.ComposeTest
import eu.darken.porter.manager.R
import eu.darken.porter.manager.ui.DialogSurface
import eu.darken.porter.manager.ui.PorterTheme
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The three action buttons share one unweighted row, so each one is measured against whatever
 * width the previous ones left over. A narrow screen with a doubled font scale is where that
 * runs out.
 */
@Config(qualifiers = "w320dp-h480dp")
class AdbPairDialogActionsLayoutTest : ComposeTest() {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun everyActionStaysOnScreenOnANarrowDisplayWithLargeText() {
        RuntimeEnvironment.setFontScale(2f)
        // Added to a container so the fragment attaches without also opening its own dialog window.
        val host = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val fragment = AdbPairDialogFragment()
        host.supportFragmentManager.beginTransaction()
            .add(android.R.id.content, fragment, "pairing").commitNow()

        composeTestRule.setContent {
            PorterTheme {
                DialogSurface(actions = { fragment.Actions() }) { fragment.Content() }
            }
        }

        composeTestRule.onNodeWithText(context.getString(R.string.development_settings)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(android.R.string.ok)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(android.R.string.cancel)).assertIsDisplayed()
    }
}
