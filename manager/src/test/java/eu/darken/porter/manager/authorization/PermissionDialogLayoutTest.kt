package eu.darken.porter.manager.authorization

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import eu.darken.porter.manager.ComposeTest
import eu.darken.porter.manager.R
import eu.darken.porter.manager.ui.PorterTheme
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The action row measures through IntrinsicSize.Max, which fails outright rather than degrading if
 * a child cannot report an intrinsic height. A narrow screen, a long-label locale and a doubled
 * font scale are the combination most likely to trigger that.
 */
@Config(qualifiers = "ta-rIN-w320dp-h480dp")
class PermissionDialogLayoutTest : ComposeTest() {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun actionsRenderOnANarrowScreenWithLongLabelsAndLargeText() {
        RuntimeEnvironment.setFontScale(2f)
        composeTestRule.setContent {
            PorterTheme {
                PermissionDialogContent(
                    stage = "ready",
                    app = RequestingApp("Porter API probe", "moe.shizuku.probe", null, null),
                    onAllow = {}, onDeny = {}, onClose = {},
                )
            }
        }
        composeTestRule.onNodeWithText(context.getString(R.string.grant_dialog_button_deny)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.grant_dialog_button_allow_always)).assertIsDisplayed()
    }
}
