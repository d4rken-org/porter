package eu.darken.porter.manager.authorization

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import eu.darken.porter.manager.R
import eu.darken.porter.manager.TestApplication
import eu.darken.porter.manager.utils.PorterStateMachine
import eu.darken.porter.manager.utils.UserHandleCompat
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

    private fun launch(uid: Int = 10123): ActivityScenario<RequestPermissionActivity> = ActivityScenario.launch<RequestPermissionActivity>(
        Intent(context, RequestPermissionActivity::class.java)
            .putExtra("uid", uid).putExtra("pid", 4242).putExtra("requestCode", 7)
            .putExtra("applicationInfo", context.applicationInfo)
    ).also { scenario = it }
    private fun awaitText(text: String) = compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    /** Lets the grace run out that follows the buttons appearing or the prompt changing app. */
    private fun pastGrace() = ShadowSystemClock.advanceBy(Duration.ofMillis(RequestPermissionActivity.SUPERSEDE_GRACE))
    /** Robolectric retires a finished activity on the next idle; accept either the finishing flag or the destroyed state. */
    private fun awaitDestroyed(scenario: ActivityScenario<*>) = compose.waitUntil(5_000) {
        scenario.state == Lifecycle.State.DESTROYED || runCatching {
            var finishing = false
            scenario.onActivity { finishing = it.isFinishing }
            finishing
        }.getOrDefault(true)
    }

    @Test fun readyPromptNamesTheRequestingPackageAndWhatAccessGrants() {
        launch()
        awaitText(allow)
        compose.onNodeWithText(context.packageName).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.porter_permission_detail)).assertIsDisplayed()
        compose.onNodeWithText(deny).assertIsDisplayed()
        compose.onNodeWithText(allow).assertIsDisplayed()
    }

    @Test fun aTapAsTheButtonsAppearDecidesNothing() {
        // Another app can start the prompt just under a finger that is already coming down.
        val scenario = launch()
        awaitText(allow)
        compose.onNodeWithText(allow).performClick()
        assertTrue(gateway.replies.isEmpty())
        assertEquals(Lifecycle.State.RESUMED, scenario.state)

        pastGrace()
        compose.onNodeWithText(allow).performClick()
        awaitDestroyed(scenario)
        assertEquals(listOf(FakePermissionGateway.Reply(10123, 4242, 7, allowed = true, onetime = false)), gateway.replies)
    }

    @Test fun promptSurvivesRecreationAndAllowRepliesOnceThenCloses() {
        val scenario = launch()
        awaitText(allow)
        scenario.recreate()
        awaitText(allow)
        assertTrue(gateway.replies.isEmpty())
        pastGrace()
        compose.onNodeWithText(allow).performClick()
        awaitDestroyed(scenario)
        assertEquals(listOf(FakePermissionGateway.Reply(10123, 4242, 7, allowed = true, onetime = false)), gateway.replies)
    }

    @Test fun denyRepliesOneTimeAndCloses() {
        val scenario = launch()
        awaitText(deny)
        pastGrace()
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

    @Test fun theUserIdIsShownAtOnceAndOnlyItsNameWaitsForTheService() {
        PorterStateMachine.instance.set(PorterStateMachine.State.STOPPED)
        val otherUserId = UserHandleCompat.myUserId() + 1
        launch(uid = otherUserId * UserHandleCompat.PER_USER_RANGE + 10123)
        awaitText(allow)
        // The id is what separates two copies of one package in two profiles, which are otherwise
        // identical down to the icon, so it cannot wait on a service that may never answer.
        compose.onNodeWithText("$otherUserId").assertIsDisplayed()
        // A name, unlike an id, would be guessed. Without the binder the lookup can only say
        // "Unknown", and that must not reach the screen.
        val placeholder = "Unknown ($otherUserId)"
        val shown = runCatching {
            compose.waitUntil(2_000) { compose.onAllNodesWithText(placeholder).fetchSemanticsNodes().isNotEmpty() }
        }.isSuccess
        assertFalse("the prompt rendered \"$placeholder\" as the requesting user", shown)
    }

    @Test fun aSecondRequestRetargetsThePromptAndRefusesTheOneItReplaced() {
        // The platform delivering it is what the device check covers; this pins what happens then.
        val scenario = launch()
        awaitText(allow)
        scenario.onActivity {
            it.onNewIntent(Intent(context, RequestPermissionActivity::class.java)
                .putExtra("uid", 10123).putExtra("pid", 4242).putExtra("requestCode", 8)
                .putExtra("applicationInfo", context.applicationInfo))
        }
        assertEquals(listOf(FakePermissionGateway.Reply(10123, 4242, 7, allowed = false, onetime = true)),
                     gateway.replies)
        awaitText(allow)
        pastGrace()
        compose.onNodeWithText(allow).performClick()
        awaitDestroyed(scenario)
        assertEquals(FakePermissionGateway.Reply(10123, 4242, 8, allowed = true, onetime = false),
                     gateway.replies.last())
    }

    @Test fun aTapLandingBeforeTheNewRequestHasBeenSeenDecidesNothing() {
        // supersede() changes which request an allow grants before the frame naming the new app is
        // drawn, so a tap already on its way would answer for an app the user never looked at.
        val other = ApplicationInfo().apply {
            packageName = "eu.darken.porter.probe.legacy"
            nonLocalizedLabel = "Shizuku API probe"
        }
        val scenario = launch()
        awaitText(allow)
        pastGrace()
        scenario.onActivity {
            it.onNewIntent(Intent(context, RequestPermissionActivity::class.java)
                .putExtra("uid", 10999).putExtra("pid", 5151).putExtra("requestCode", 8)
                .putExtra("applicationInfo", other))
        }
        awaitText(other.packageName)
        compose.onNodeWithText(allow).performClick()
        // Only the refusal of the request that was replaced; nothing was granted to either app.
        assertEquals(listOf(FakePermissionGateway.Reply(10123, 4242, 7, allowed = false, onetime = true)),
                     gateway.replies)
        assertEquals(Lifecycle.State.RESUMED, scenario.state)

        pastGrace()
        compose.onNodeWithText(allow).performClick()
        awaitDestroyed(scenario)
        assertEquals(FakePermissionGateway.Reply(10999, 5151, 8, allowed = true, onetime = false),
                     gateway.replies.last())
    }

    /** An app whose icon decode blocks, which is the wait the prompt used to keep the old name for. */
    private class SlowIconApp(pkg: String, val gate: CountDownLatch) : ApplicationInfo() {
        init { packageName = pkg; nonLocalizedLabel = "Shizuku API probe" }
        override fun loadIcon(pm: android.content.pm.PackageManager?): Drawable {
            gate.await(10, TimeUnit.SECONDS)
            return ColorDrawable(0xFF00FF00.toInt())
        }
    }

    @Test fun theNewAppIsNamedWhileItsIconIsStillDecoding() {
        // The name must come from the composition the request change causes, not from the icon
        // decode that follows it: the requesting app controls how long that decode takes.
        val gate = CountDownLatch(1)
        val other = SlowIconApp("eu.darken.porter.probe.legacy", gate)
        val scenario = launch()
        awaitText(allow)
        compose.onNodeWithText(context.packageName).assertIsDisplayed()
        try {
            scenario.onActivity {
                // Delivered in-process, so the extra is never parceled and this instance survives.
                it.onNewIntent(Intent(context, RequestPermissionActivity::class.java)
                    .putExtra("uid", 10999).putExtra("pid", 5151).putExtra("requestCode", 8)
                    .putExtra("applicationInfo", other))
            }
            awaitText(other.packageName)
            // The decode has not returned, so this is the composition and not the producer.
            compose.onNodeWithText(context.packageName).assertDoesNotExist()
        } finally {
            gate.countDown()
        }
    }

    @Test fun aTapBeforeTheNewNameIsDrawnDecidesNothing() {
        // supersede() repoints the model the moment the intent lands, so a tap already queued
        // would answer for an app no frame has named. The timer cannot cover its own start.
        //
        // Asked directly rather than through performClick, which resolves its node by idling
        // first: that recomposes and stamps the timer, so a click can only ever exercise the
        // timer and would pass with the invariant deleted.
        val other = ApplicationInfo().apply {
            packageName = "eu.darken.porter.probe.legacy"
            nonLocalizedLabel = "Shizuku API probe"
        }
        val scenario = launch()
        awaitText(allow)
        pastGrace()
        scenario.onActivity {
            assertTrue("a prompt that never changed app answers normally", it.userIsAnswering())
            it.onNewIntent(Intent(context, RequestPermissionActivity::class.java)
                .putExtra("uid", 10999).putExtra("pid", 5151).putExtra("requestCode", 8)
                .putExtra("applicationInfo", other))
            // Same main-thread message as the delivery: nothing has recomposed, so the timer has
            // not been stamped and only the drawn-vs-pointed-at check can refuse this.
            assertFalse("a tap before the new name is drawn must not decide", it.userIsAnswering())
        }
    }


    @Test fun aRebuiltProcessReadsTheRequestItAdoptedNotTheOneThatLaunchedIt() {
        // Read through the scenario rather than a raw Robolectric activity: one built by hand
        // leaves a window behind in the JVM the module's other tests share, and the next test to
        // assert on focus never gets it.
        //
        // What this cannot cover is onCreate preferring restored() over requested(intent), which
        // scenario.recreate() also cannot show: it keeps the setIntent intent, so the bundle and
        // the intent name the same request and neither can be seen to win. What a real activity
        // writes into that bundle is pinned by onSaveInstanceStateCarriesTheAdoptedRequest.
        val other = ApplicationInfo().apply {
            packageName = "eu.darken.porter.probe.legacy"
            nonLocalizedLabel = "Shizuku API probe"
        }
        val saved = Bundle().apply {
            putInt("asked.uid", 10999)
            putInt("asked.pid", 5151)
            putInt("asked.code", 8)
            putParcelable("asked.info", other)
        }
        val scenario = launch()
        awaitText(allow)
        scenario.onActivity {
            val adopted = it.restored(saved)
            assertEquals(10999, adopted?.uid)
            assertEquals(5151, adopted?.pid)
            assertEquals(8, adopted?.code)
            assertEquals(other.packageName, adopted?.info?.packageName)
            // A process starting fresh has no saved copy and falls through to its intent.
            assertNull(it.restored(null))
            assertNull("a bundle naming no caller is not a request", it.restored(Bundle()))
        }
    }

    @Test fun onSaveInstanceStateCarriesTheAdoptedRequest() {
        // onSaveInstanceState is the only copy a rebuilt process gets; setIntent does not survive
        // process death, so what it wrote is what decides which caller is answered.
        val other = ApplicationInfo().apply {
            packageName = "eu.darken.porter.probe.legacy"
            nonLocalizedLabel = "Shizuku API probe"
        }
        val scenario = launch()
        awaitText(allow)
        scenario.onActivity {
            it.onNewIntent(Intent(context, RequestPermissionActivity::class.java)
                .putExtra("uid", 10999).putExtra("pid", 5151).putExtra("requestCode", 8)
                .putExtra("applicationInfo", other))
        }
        val saved = Bundle()
        scenario.onActivity { it.onSaveInstanceState(saved) }
        assertEquals(10999, saved.getInt("asked.uid", -1))
        assertEquals(5151, saved.getInt("asked.pid", -1))
        assertEquals(8, saved.getInt("asked.code", -1))
        @Suppress("DEPRECATION")
        assertEquals(other.packageName, saved.getParcelable<ApplicationInfo>("asked.info")?.packageName)
    }

    @Test fun oneAppAskingAgainCannotKeepThePromptFromBeingAnswered() {
        // The name on screen does not change, so there is nothing to mislead a tap. Arming the
        // guard here would let an app that re-asks on a timer keep every tap from landing.
        val scenario = launch()
        awaitText(allow)
        repeat(3) { round ->
            scenario.onActivity {
                it.onNewIntent(Intent(context, RequestPermissionActivity::class.java)
                    .putExtra("uid", 10123).putExtra("pid", 4242).putExtra("requestCode", 8 + round)
                    .putExtra("applicationInfo", context.applicationInfo))
            }
        }
        awaitText(allow)
        pastGrace()
        compose.onNodeWithText(allow).performClick()
        awaitDestroyed(scenario)
        assertEquals(FakePermissionGateway.Reply(10123, 4242, 10, allowed = true, onetime = false),
                     gateway.replies.last())
    }

    @Test fun aSecondRequestFromAnotherAppIsWhatThePromptThenNames() {
        // Answering for one app while naming another is the spoof this prompt exists to prevent.
        val other = ApplicationInfo().apply {
            packageName = "eu.darken.porter.probe.legacy"
            nonLocalizedLabel = "Shizuku API probe"
        }
        val scenario = launch()
        awaitText(allow)
        compose.onNodeWithText(context.packageName).assertIsDisplayed()
        scenario.onActivity {
            it.onNewIntent(Intent(context, RequestPermissionActivity::class.java)
                .putExtra("uid", 10999).putExtra("pid", 5151).putExtra("requestCode", 8)
                .putExtra("applicationInfo", other))
        }
        awaitText(other.packageName)
        compose.onNodeWithText(other.packageName).assertIsDisplayed()
    }

    @Test fun theRequestBeingAskedAboutIsWhatARecreationRestores() {
        // A configuration change keeps the intent setIntent wrote. Process death does not, which
        // is what aRebuiltProcessAnswersTheRequestItAdoptedNotTheOneThatLaunchedIt covers.
        val scenario = launch()
        awaitText(allow)
        scenario.onActivity {
            it.onNewIntent(Intent(context, RequestPermissionActivity::class.java)
                .putExtra("uid", 10123).putExtra("pid", 4242).putExtra("requestCode", 8)
                .putExtra("applicationInfo", context.applicationInfo))
        }
        scenario.onActivity { assertEquals(8, it.intent.getIntExtra("requestCode", -1)) }
        scenario.recreate()
        awaitText(allow)
        pastGrace()
        compose.onNodeWithText(allow).performClick()
        awaitDestroyed(scenario)
        assertEquals(FakePermissionGateway.Reply(10123, 4242, 8, allowed = true, onetime = false),
                     gateway.replies.last())
    }

    @Test fun aRefusedNewcomerIsNotWhatARecreationRestores() {
        val scenario = launch()
        awaitText(allow)
        pastGrace()
        compose.onNodeWithText(allow).performClick()
        scenario.onActivity {
            it.onNewIntent(Intent(context, RequestPermissionActivity::class.java)
                .putExtra("uid", 10999).putExtra("pid", 5151).putExtra("requestCode", 8)
                .putExtra("applicationInfo", context.applicationInfo))
            assertEquals(7, it.intent.getIntExtra("requestCode", -1))
        }
    }

    @Test fun aSecondRequestWithoutCallerIdentityLeavesThePromptAlone() {
        val scenario = launch()
        awaitText(allow)
        scenario.onActivity { it.onNewIntent(Intent(context, RequestPermissionActivity::class.java)) }
        compose.onNodeWithText(context.packageName).assertIsDisplayed()
        assertTrue(gateway.replies.isEmpty())
    }

    @Test fun requestWithoutCallerIdentityClosesWithoutReply() {
        val scenario = ActivityScenario.launch<RequestPermissionActivity>(Intent(context, RequestPermissionActivity::class.java)).also { this.scenario = it }
        awaitDestroyed(scenario)
        assertTrue(gateway.replies.isEmpty())
    }
}
