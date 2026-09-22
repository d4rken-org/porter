package eu.darken.porter.manager.authorization

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import eu.darken.porter.manager.utils.PorterStateMachine.State
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

internal class FakePermissionGateway(
    val service: MutableStateFlow<State> = MutableStateFlow(State.RUNNING),
    var canGrant: Boolean = true,
    var failCheck: Exception? = null,
) : PermissionGateway {
    data class Reply(val uid: Int, val pid: Int, val code: Int, val allowed: Boolean, val onetime: Boolean)
    val replies = mutableListOf<Reply>()
    var checks = 0
    var dispatchFailure: Exception? = null
    /** When set, the permission check parks here so a test can act while it is in flight. */
    var holdCheck: CompletableDeferred<Unit>? = null
    override fun serviceStates(): Flow<State> = service
    override suspend fun canGrantPermissions(): Boolean {
        holdCheck?.await()
        checks++
        failCheck?.let { throw it }
        return canGrant
    }
    override fun dispatch(uid: Int, pid: Int, code: Int, allowed: Boolean, onetime: Boolean) {
        replies += Reply(uid, pid, code, allowed, onetime)
        dispatchFailure?.let { throw it }
    }
}

/** One request must produce exactly one reply, across duplicate taps, recreation and process death. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PermissionViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val gateway = FakePermissionGateway()
    private val handle = SavedStateHandle()

    @Before fun main() { Dispatchers.setMain(dispatcher) }
    @After fun restore() { Dispatchers.resetMain() }

    private fun model(state: SavedStateHandle = handle) = PermissionViewModel(state, gateway).also { it.initialize(10123, 4242, 7) }
    private fun advance() = dispatcher.scheduler.advanceUntilIdle()
    private fun runPending() = dispatcher.scheduler.runCurrent()
    private fun restoredFrom(source: SavedStateHandle) = SavedStateHandle(source.keys().associateWith { source.get<Any>(it) })

    @Test fun runningServiceThatCanGrantBecomesReady() {
        val model = model()
        assertEquals("waiting", model.stage.value)
        advance()
        assertEquals("ready", model.stage.value)
        assertTrue(gateway.replies.isEmpty())
    }

    @Test fun allowIsDispatchedOnceAsPermanentGrant() {
        val model = model(); advance()
        model.reply(true)
        model.reply(false)
        model.reply(true)
        assertEquals(listOf(FakePermissionGateway.Reply(10123, 4242, 7, allowed = true, onetime = false)), gateway.replies)
        assertEquals("finished", model.stage.value)
        assertEquals(true, handle["replied"])
    }

    @Test fun denyIsDispatchedAsOneTime() {
        val model = model(); advance()
        model.reply(false)
        assertEquals(listOf(FakePermissionGateway.Reply(10123, 4242, 7, allowed = false, onetime = true)), gateway.replies)
    }

    @Test fun recreatedModelAfterReplyFinishesWithoutAskingTheServiceAgain() {
        model().also { advance(); it.reply(true) }
        gateway.canGrant = false
        val restored = model(restoredFrom(handle))
        advance()
        assertEquals("finished", restored.stage.value)
        assertEquals(1, gateway.checks)
        assertEquals(1, gateway.replies.size)
    }

    @Test fun restoredReadyPromptRepeatsTheServiceCheckAndKeepsAnEarlyDecision() {
        val inFlight = CompletableDeferred<Unit>().also { gateway.holdCheck = it }
        val restored = model(SavedStateHandle(mapOf("stage" to "ready")))
        runPending()
        assertEquals("ready", restored.stage.value)
        assertEquals(0, gateway.checks)
        restored.reply(true)
        assertEquals("finished", restored.stage.value)
        assertEquals(1, gateway.replies.size)
        inFlight.complete(Unit)
        advance()
        assertEquals(1, gateway.checks)
        assertEquals("finished", restored.stage.value)
        restored.reply(true)
        assertEquals(1, gateway.replies.size)
    }

    @Test fun restoredReadyPromptWhoseServiceLostGrantAuthorityTurnsLimited() {
        gateway.canGrant = false
        val restored = model(SavedStateHandle(mapOf("stage" to "ready")))
        advance()
        assertEquals(1, gateway.checks)
        assertEquals("limited", restored.stage.value)
        assertEquals(listOf(FakePermissionGateway.Reply(10123, 4242, 7, allowed = false, onetime = true)), gateway.replies)
    }

    @Test fun aSecondRequestBecomesTheOneTheAnswerBelongsTo() {
        // Every prompt is a new document of one component, so an app that asks again while its
        // prompt is up - which is what a relaunched activity does - is answered here or nowhere.
        val model = model(); advance()
        assertTrue(model.supersede(10123, 4242, 8))
        assertEquals(listOf(FakePermissionGateway.Reply(10123, 4242, 7, allowed = false, onetime = true)),
                     gateway.replies)
        model.reply(true)
        assertEquals(FakePermissionGateway.Reply(10123, 4242, 8, allowed = true, onetime = false),
                     gateway.replies.last())
        assertEquals(2, gateway.replies.size)
    }

    @Test fun theRequestReplacedIsRefusedRatherThanLeftUnanswered() {
        // Its caller is suspended until something answers, and a one-time no lets it ask again.
        val model = model(); advance()
        model.supersede(10999, 5151, 8)
        assertEquals(listOf(FakePermissionGateway.Reply(10123, 4242, 7, allowed = false, onetime = true)),
                     gateway.replies)
        assertEquals("ready", model.stage.value)
    }

    @Test fun theSameRequestArrivingTwiceReplacesNothing() {
        val model = model(); advance()
        assertTrue(model.supersede(10123, 4242, 7))
        assertTrue(gateway.replies.isEmpty())
    }

    @Test fun aRequestFromTheSameAppAfterTheAnswerIsGivenThatAnswer() {
        // The server applies a result to every record of the uid it names, so refusing this one
        // would take back the grant the user just made and leave the saved decision saying it
        // stands. The decision covers the uid, so the answer to give is the one already given.
        val model = model(); advance()
        model.reply(true)
        assertFalse(model.supersede(10123, 9999, 8))
        assertEquals(FakePermissionGateway.Reply(10123, 9999, 8, allowed = true, onetime = false),
                     gateway.replies.last())
        assertEquals("finished", model.stage.value)
    }

    @Test fun aRequestFromTheSameAppAfterADenialIsDeniedTheSameWay() {
        val model = model(); advance()
        model.reply(false)
        assertFalse(model.supersede(10123, 9999, 8))
        assertEquals(FakePermissionGateway.Reply(10123, 9999, 8, allowed = false, onetime = true),
                     gateway.replies.last())
    }

    @Test fun aRequestFromAnotherAppAfterTheAnswerIsRefusedWithoutDisturbingIt() {
        // Nothing was decided about it, and it had no grant to lose: an app already allowed is
        // answered by the server without a prompt and never reaches one.
        val model = model(); advance()
        model.reply(true)
        assertFalse(model.supersede(10999, 5151, 8))
        assertEquals(listOf(
            FakePermissionGateway.Reply(10123, 4242, 7, allowed = true, onetime = false),
            FakePermissionGateway.Reply(10999, 5151, 8, allowed = false, onetime = true),
        ), gateway.replies)
    }

    @Test fun aRequestArrivingAfterAProcessDeathRestoreIsStillCoveredByTheAnswer() {
        model().also { advance(); it.reply(true) }
        val restored = model(restoredFrom(handle))
        advance()
        assertFalse(restored.supersede(10123, 9999, 8))
        assertEquals(FakePermissionGateway.Reply(10123, 9999, 8, allowed = true, onetime = false),
                     gateway.replies.last())
    }

    @Test fun aRequestArrivingAtAPromptThatCannotAskIsRefused() {
        // No grant authority: the prompt is showing why it cannot ask rather than asking.
        gateway.canGrant = false
        val model = model(); advance()
        assertEquals("limited", model.stage.value)
        assertFalse(model.supersede(10999, 5151, 8))
        assertEquals(FakePermissionGateway.Reply(10999, 5151, 8, allowed = false, onetime = true),
                     gateway.replies.last())
    }

    @Test fun aDispatchThatThrowsDoesNotStopThePromptAdoptingTheRequest() {
        val model = model(); advance()
        gateway.dispatchFailure = IllegalStateException("no manager binder")
        assertTrue(model.supersede(10123, 4242, 8))
        gateway.dispatchFailure = null
        model.reply(true)
        assertEquals(FakePermissionGateway.Reply(10123, 4242, 8, allowed = true, onetime = false),
                     gateway.replies.last())
    }

    @Test fun restoredModelStillWaitingRerunsTheWait() {
        gateway.service.value = State.STARTING
        val model = model(SavedStateHandle(mapOf("stage" to "waiting")))
        runPending()
        assertEquals("waiting", model.stage.value)
        assertEquals(0, gateway.checks)
        gateway.service.value = State.RUNNING
        advance()
        assertEquals("ready", model.stage.value)
        assertEquals(1, gateway.checks)
    }

    @Test fun serviceWithoutGrantAuthorityDeniesOnceAndShowsLimitedNotice() {
        gateway.canGrant = false
        val model = model(); advance()
        assertEquals("limited", model.stage.value)
        assertEquals(listOf(FakePermissionGateway.Reply(10123, 4242, 7, allowed = false, onetime = true)), gateway.replies)
        model.reply(true)
        assertEquals(1, gateway.replies.size)
        val restored = model(restoredFrom(handle))
        advance()
        assertEquals("limited", restored.stage.value)
        assertEquals(1, gateway.checks)
        assertEquals(1, gateway.replies.size)
    }

    @Test fun serviceThatNeverAppearsFinishesAfterTimeoutWithoutReply() {
        gateway.service.value = State.STARTING
        val model = model()
        dispatcher.scheduler.advanceTimeBy(PermissionViewModel.SERVICE_TIMEOUT - 1)
        assertEquals("waiting", model.stage.value)
        dispatcher.scheduler.advanceTimeBy(2)
        assertEquals("finished", model.stage.value)
        assertTrue(gateway.replies.isEmpty())
    }

    @Test fun failedServiceCheckFinishesWithoutReply() {
        gateway.failCheck = IllegalStateException("binder gone")
        val model = model(); advance()
        assertEquals("finished", model.stage.value)
        assertTrue(gateway.replies.isEmpty())
    }

    @Test fun failedDispatchStillCountsAsTheOnlyDecision() {
        gateway.dispatchFailure = IllegalStateException("binder died")
        val model = model(); advance()
        model.reply(true)
        model.reply(false)
        assertEquals(1, gateway.replies.size)
        assertEquals("finished", model.stage.value)
    }
}
