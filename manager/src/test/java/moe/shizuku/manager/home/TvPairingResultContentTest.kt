package moe.shizuku.manager.home

import android.content.Context
import androidx.compose.ui.test.*
import androidx.test.core.app.ApplicationProvider
import moe.shizuku.manager.ComposeTest
import moe.shizuku.manager.R
import moe.shizuku.manager.ui.PorterTheme
import moe.shizuku.manager.ui.DialogSurface
import org.junit.Assert.assertEquals
import org.junit.Test

class TvPairingResultContentTest : ComposeTest() {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Test fun failureShowsLastingReasonAndRetryAction() {
        var retries = 0
        composeTestRule.setContent {
            PorterTheme { DialogSurface { TvPairingResultContent(false, "Pairing expired", { retries++ }, {}) } }
        }
        composeTestRule.onNodeWithText("Pairing expired").assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.notification_adb_pairing_retry)).performClick()
        assertEquals(1, retries)
    }
    @Test fun successExplainsStartingIsSeparateAndOffersStart() {
        var starts = 0
        composeTestRule.setContent {
            PorterTheme { DialogSurface {
                TvPairingResultContent(true, context.getString(R.string.notification_adb_pairing_succeed_text), { starts++ }, {})
            } }
        }
        composeTestRule.onNodeWithText(context.getString(R.string.notification_adb_pairing_succeed_text)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.home_root_button_start)).performClick()
        assertEquals(1, starts)
    }
}
