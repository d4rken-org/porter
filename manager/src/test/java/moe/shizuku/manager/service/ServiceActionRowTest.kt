package moe.shizuku.manager.service

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import moe.shizuku.manager.ComposeTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceActionRowTest : ComposeTest() {
    private fun verify(rtl: Boolean, stacked: Boolean) {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                ServiceActionRow(
                    start = { Box(Modifier.size(90.dp, 48.dp).testTag("stop")) },
                    end = { Box(Modifier.size(90.dp, 48.dp).testTag("update")) },
                    modifier = Modifier.width(if (stacked) 140.dp else 240.dp).testTag("row"),
                )
            }
        }
        val row = composeTestRule.onNodeWithTag("row").fetchSemanticsNode().boundsInRoot
        val stop = composeTestRule.onNodeWithTag("stop").fetchSemanticsNode().boundsInRoot
        val update = composeTestRule.onNodeWithTag("update").fetchSemanticsNode().boundsInRoot
        assertEquals(if (rtl) row.right else row.left, if (rtl) stop.right else stop.left, 0.1f)
        assertEquals(if (rtl) row.left else row.right, if (rtl) update.left else update.right, 0.1f)
        if (stacked) assertTrue(update.top > stop.bottom) else assertEquals(stop.top, update.top, 0.1f)
    }
    @Test fun wideActionsKeepTheirEdges() = verify(rtl = false, stacked = false)
    @Test fun narrowActionsStackAndKeepTheirEdges() = verify(rtl = false, stacked = true)
    @Test fun wideRtlActionsMirrorTheirEdges() = verify(rtl = true, stacked = false)
    @Test fun narrowRtlActionsStackAndMirrorTheirEdges() = verify(rtl = true, stacked = true)
}
