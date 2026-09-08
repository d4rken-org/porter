package moe.shizuku.manager.authorization

import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import moe.shizuku.manager.utils.ShizukuStateMachine.State
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import rikka.shizuku.ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED
import rikka.shizuku.ShizukuApiConstants.REQUEST_PERMISSION_REPLY_IS_ONETIME

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
    override fun dispatch(uid: Int, pid: Int, code: Int, data: Bundle) {
        replies += Reply(uid, pid, code, data.getBoolean(REQUEST_PERMISSION_REPLY_ALLOWED), data.getBoolean(REQUEST_PERMISSION_REPLY_IS_ONETIME))
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
