package moe.shizuku.manager.starter

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.shizuku.manager.TestApplication
import moe.shizuku.manager.starter.ServiceUpdateStore.Phase
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [34])
class ServiceUpdateControllerTest {
    private val preferences = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("service-update-test", 0)
    private val store = ServiceUpdateStore(preferences, "installed:debug")
    private var enabled = false
    private var primary = true
    private var launches = 0
    @Before fun reset() { preferences.edit().clear().commit() }
    private fun controller(action: suspend (() -> Unit, () -> Unit) -> Unit = { before, launched -> before(); launches++; launched() }) =
        ServiceUpdateController(store, { primary }, { enabled }, action)

    @Test fun openingRestoresStateWithoutStartingAnything() {
        store.write(Phase.LAUNCHED)
        val controller = controller()
        assertTrue(controller.state.value.failed)
        assertEquals(0, launches)
    }
    @Test fun automaticIsOffUnlessEnabledButManualStillWorks() = runTest {
        val controller = controller()
        assertEquals(ServiceUpdateController.Outcome.SKIPPED, controller.update(automatic = true))
        assertNull(store.read())
        assertEquals(ServiceUpdateController.Outcome.UPDATED, controller.update(automatic = false))
        assertEquals(1, launches)
    }
    @Test fun failedAutomaticAttemptIsBoundedButManualCanRetry() = runTest {
        enabled = true
        val failed = controller { before, _ -> before(); launches++; error("starter rejected APK") }
        assertEquals(ServiceUpdateController.Outcome.FAILED, failed.update(automatic = true))
        assertEquals(Phase.FAILED, store.read()!!.phase)
        assertEquals(ServiceUpdateController.Outcome.SKIPPED, controller().update(automatic = true))
        assertEquals(1, launches)
        assertEquals(ServiceUpdateController.Outcome.UPDATED, controller().update(automatic = false))
        assertEquals(2, launches)
    }
    @Test fun disablingWhileQueuedPreventsLaunchAndDoesNotRecordAttempt() = runTest {
        enabled = true
        val controller = controller { before, launched -> enabled = false; before(); launches++; launched() }
        assertEquals(ServiceUpdateController.Outcome.SKIPPED, controller.update(automatic = true))
        assertNull(store.read())
        assertEquals(0, launches)
    }
    @Test fun manualClickDuringBackgroundHandoffDoesNotQueueAnotherReplacement() = runTest {
        enabled = true
        val release = CompletableDeferred<Unit>()
        val controller = controller { before, launched -> before(); launches++; launched(); release.await() }
        val first = async { controller.update(automatic = true) }
        runCurrent()
        assertTrue(controller.state.value.running)
        assertEquals(ServiceUpdateController.Outcome.BUSY, controller.update(automatic = false))
        release.complete(Unit)
        first.await()
        assertEquals(1, launches)
        assertFalse(controller.state.value.running)
    }
    @Test fun secondaryUsersCannotStartEitherKindOfUpdate() = runTest {
        primary = false; enabled = true
        val controller = controller()
        assertEquals(ServiceUpdateController.Outcome.SKIPPED, controller.update(automatic = true))
        assertEquals(ServiceUpdateController.Outcome.SKIPPED, controller.update(automatic = false))
        assertEquals(0, launches)
    }
    @Test fun interruptedHandoffIsReconciledOnlyAgainstTheMatchingService() = runTest {
        store.write(Phase.LAUNCHED)
        val controller = controller()
        controller.reconcile { false }
        assertTrue(controller.state.value.failed)
        controller.reconcile { true }
        assertEquals(Phase.SUCCEEDED, store.read()!!.phase)
        assertFalse(controller.state.value.failed)
    }
    @Test fun cancellationPreservesHandoffPhaseForColdStart() = runTest {
        val controller = controller { before, launched -> before(); launched(); throw CancellationException("worker stopped") }
        assertTrue(runCatching { controller.update(automatic = false) }.exceptionOrNull() is CancellationException)
        assertEquals(Phase.LAUNCHED, store.read()!!.phase)
        assertFalse(controller.state.value.running)
        assertTrue(controller().state.value.failed)
    }
    @Test fun newBuildDoesNotInheritOldAttemptOrFailure() {
        store.write(Phase.FAILED, "old failure")
        val newer = ServiceUpdateStore(preferences, "next:debug")
        assertNull(newer.read())
    }
}
