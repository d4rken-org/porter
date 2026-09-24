package eu.darken.porter.manager.compatibility

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.test.core.app.ApplicationProvider
import eu.darken.porter.manager.ComposeTest
import eu.darken.porter.manager.R
import eu.darken.porter.manager.management.AppsViewModel
import eu.darken.porter.manager.ui.PorterTheme
import org.junit.Assert.assertEquals
import org.junit.Test

class CompatibilityUsageTest : ComposeTest() {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun onlyAKnownCountOpensTheAppList() {
        var running by mutableStateOf(true)
        var apps by mutableStateOf(AppsViewModel.State(loading = false))
        var opens = 0
        composeTestRule.setContent { PorterTheme { CompatibilityUsage(running, apps) { opens++ } } }
        composeTestRule.onNodeWithText(context.resources.getQuantityString(R.plurals.compat_usage_count, 0, 0)).performClick()
        assertEquals(1, opens)
        composeTestRule.runOnIdle { apps = apps.copy(error = IllegalStateException("Disconnected")) }
        composeTestRule.onNodeWithText(context.getString(R.string.compat_usage_unknown)).performClick()
        composeTestRule.runOnIdle { running = false }
        composeTestRule.onNodeWithText(context.getString(R.string.compat_usage_stopped)).performClick()
        assertEquals(1, opens)
    }
}
