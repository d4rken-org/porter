package moe.shizuku.manager.authorization

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import moe.shizuku.manager.R
import moe.shizuku.manager.TestApplication
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The real prompt activity through recreation: one decision, one reply, no re-prompt. */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RequestPermissionActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val gateway = FakePermissionGateway()
    private var scenario: ActivityScenario<RequestPermissionActivity>? = null
    private fun string(id: Int) = context.getString(id)
    private val allow get() = string(R.string.grant_dialog_button_allow_always)
    private val deny get() = string(R.string.grant_dialog_button_deny)

    @Before fun inject() { PermissionViewModel.gatewayOverride = gateway }
    @After fun cleanUp() { scenario?.close(); PermissionViewModel.gatewayOverride = null }

    private fun launch(): ActivityScenario<RequestPermissionActivity> = ActivityScenario.launch<RequestPermissionActivity>(
        Intent(context, RequestPermissionActivity::class.java)
            .putExtra("uid", 10123).putExtra("pid", 4242).putExtra("requestCode", 7)
            .putExtra("applicationInfo", context.applicationInfo)
    ).also { scenario = it }
    private fun awaitText(text: String) = compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    /** Robolectric retires a finished activity on the next idle; accept either the finishing flag or the destroyed state. */
    private fun awaitDestroyed(scenario: ActivityScenario<*>) = compose.waitUntil(5_000) {
        scenario.state == Lifecycle.State.DESTROYED || runCatching {
            var finishing = false
            scenario.onActivity { finishing = it.isFinishing }
            finishing
        }.getOrDefault(true)
    }

    @Test fun promptSurvivesRecreationAndAllowRepliesOnceThenCloses() {
        val scenario = launch()
        awaitText(allow)
        scenario.recreate()
        awaitText(allow)
        assertTrue(gateway.replies.isEmpty())
        compose.onNodeWithText(allow).performClick()
        awaitDestroyed(scenario)
        assertEquals(listOf(FakePermissionGateway.Reply(10123, 4242, 7, allowed = true, onetime = false)), gateway.replies)
    }

    @Test fun denyRepliesOneTimeAndCloses() {
        val scenario = launch()
        awaitText(deny)
        compose.onNodeWithText(deny).performClick()
        awaitDestroyed(scenario)
        assertEquals(listOf(FakePermissionGateway.Reply(10123, 4242, 7, allowed = false, onetime = true)), gateway.replies)
    }

    @Test fun limitedServiceDeniesOnceAndStaysDeniedAcrossRecreation() {
        gateway.canGrant = false
        val scenario = launch()
        val ok = string(android.R.string.ok)
        awaitText(ok)
        assertEquals(1, gateway.replies.size)
        assertFalse(gateway.replies.single().allowed)
        scenario.recreate()
        awaitText(ok)
        assertEquals(1, gateway.replies.size)
        compose.onAllNodesWithText(allow).assertCountEquals(0)
        compose.onNodeWithText(ok).performClick()
        awaitDestroyed(scenario)
        assertEquals(1, gateway.replies.size)
    }

    @Test fun requestWithoutCallerIdentityClosesWithoutReply() {
        val scenario = ActivityScenario.launch<RequestPermissionActivity>(Intent(context, RequestPermissionActivity::class.java)).also { this.scenario = it }
        awaitDestroyed(scenario)
        assertTrue(gateway.replies.isEmpty())
    }
}
