package moe.shizuku.manager.home

import android.content.Context
import android.provider.Settings
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.*
import androidx.test.core.app.ApplicationProvider
import moe.shizuku.manager.ComposeTest
import moe.shizuku.manager.R
import moe.shizuku.manager.ui.PorterTheme
import moe.shizuku.manager.ui.DialogSurface
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.Shadows.shadowOf

class TvPairingResultContentTest : ComposeTest() {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Test fun failureShowsLastingReasonAndRetryAction() {
        var retries = 0
        composeTestRule.setContent {
            PorterTheme { DialogSurface { TvPairingResultContent(false, "Pairing expired", { retries++ }, {}) } }
        }
        composeTestRule.onNodeWithText("Pairing expired").assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.porter_tv_pairing_complete)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.notification_adb_pairing_retry)).performClick()
        assertEquals(1, retries)
    }

    @Test fun cleanupPendingDoesNotClaimCompletionOrAllowRetry() {
        composeTestRule.setContent {
            PorterTheme { DialogSurface {
                TvPairingResultContent(false, "Pairing expired", {}, {}, serviceEnabled = true)
            } }
        }
        composeTestRule.onNodeWithText("Pairing expired").assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.porter_tv_pairing_disabling)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.porter_tv_pairing_complete)).assertDoesNotExist()
        composeTestRule.onNodeWithText(context.getString(R.string.notification_adb_pairing_retry)).assertIsNotEnabled()
    }

    @Test fun delayedCleanupOffersSettingsWithoutChangingPairingOutcome() {
        var opened = 0
        composeTestRule.setContent {
            PorterTheme { DialogSurface {
                TvPairingResultContent(true, "Paired", {}, {}, serviceEnabled = true,
                    cleanupDelayed = true, onAccessibilitySettings = { opened++ })
            } }
        }
        composeTestRule.onNodeWithText("Paired").assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.porter_tv_pairing_cleanup_delayed)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.porter_tv_pairing_open_accessibility)).performClick()
        assertEquals(1, opened)
        composeTestRule.onNodeWithText(context.getString(R.string.home_root_button_start)).assertIsNotEnabled()
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

    @Test fun cleanupObservesServiceDisableAndStopsObservingWhenDisposed() {
        val resolver = context.contentResolver
        val uri = Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val existingObservers = shadowOf(resolver).getContentObservers(uri).toSet()
        val visible = mutableStateOf(true)
        Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "${context.packageName}/moe.shizuku.manager.adb.AdbPairingAccessibilityService")
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            val inputMode = LocalInputModeManager.current
            SideEffect { inputMode.requestInputMode(InputMode.Keyboard) }
            if (visible.value) {
                val state = rememberTvPairingCleanupState(context)
                PorterTheme { DialogSurface {
                    TvPairingResultContent(false, "Pairing expired", {}, {},
                        serviceEnabled = state.serviceEnabled, cleanupDelayed = state.cleanupDelayed)
                } }
            }
        }
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.onNodeWithText(context.getString(R.string.porter_tv_pairing_disabling)).assertIsDisplayed()
        composeTestRule.mainClock.advanceTimeBy(5_100)
        composeTestRule.onNodeWithText(context.getString(R.string.porter_tv_pairing_cleanup_delayed)).assertIsDisplayed()
        composeTestRule.runOnIdle {
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "")
            resolver.notifyChange(uri, null)
            shadowOf(Looper.getMainLooper()).idle()
        }
        composeTestRule.mainClock.advanceTimeBy(1_000)
        composeTestRule.onNodeWithText(context.getString(R.string.porter_tv_pairing_complete)).assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.notification_adb_pairing_retry)).assertIsEnabled().assertIsFocused()
        composeTestRule.runOnIdle { visible.value = false }
        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Pairing expired").assertDoesNotExist()
        composeTestRule.runOnIdle {
            assertEquals(existingObservers, shadowOf(resolver).getContentObservers(uri).toSet())
        }
    }
}
