package eu.darken.porter.manager.home

import android.content.Context
import androidx.compose.ui.test.*
import androidx.test.core.app.ApplicationProvider
import eu.darken.porter.manager.ComposeTest
import eu.darken.porter.manager.R
import eu.darken.porter.manager.ui.PorterTheme
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.annotation.Config

@Config(qualifiers = "w640dp-h240dp-land", fontScale = 2.0f)
class UpdateDownloadDialogShortWindowTest : ComposeTest() {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun string(id: Int) = context.getString(id)

    @Test fun theLastOptionCanBeReachedAndChosenInAShortWindow() {
        var recorded: UpdateAction? = null
        composeTestRule.setContent {
            PorterTheme(dark = false) {
                UpdateDownloadDialog("porter-v0.7.0-rc0-release.apk", automaticAvailable = true,
                    onConfirm = { recorded = it }, onDismiss = {})
            }
        }
        composeTestRule.onNode(hasText(string(R.string.updater_install_automatic)) and isSelectable())
            .performScrollTo()
            .performClick()
        composeTestRule.onNodeWithText(string(R.string.updater_continue)).performClick()
        assertEquals(UpdateAction.INSTALL_AUTOMATIC, recorded)
    }
}
