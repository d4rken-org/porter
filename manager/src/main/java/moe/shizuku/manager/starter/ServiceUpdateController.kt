package moe.shizuku.manager.starter

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import moe.shizuku.manager.starter.ServiceUpdateStore.Phase

internal class ServiceUpdateController(
    private val store: ServiceUpdateStore,
    private val isPrimaryUser: () -> Boolean,
    private val automaticEnabled: () -> Boolean,
    private val replace: suspend (beforeLaunch: () -> Unit, onLaunched: () -> Unit) -> Unit,
    private val onFinished: () -> Unit = {},
) {
    data class State(val running: Boolean = false, val failed: Boolean = false)
    enum class Outcome { UPDATED, SKIPPED, BUSY, FAILED }
    private class Skipped : RuntimeException()
    private val mutex = Mutex()
    private fun restoredState() = State(failed = store.read()?.phase?.let { it != Phase.SUCCEEDED } == true)
    private val mutableState = MutableStateFlow(restoredState())
    val state = mutableState.asStateFlow()

    suspend fun update(automatic: Boolean): Outcome {
        if (!isPrimaryUser()) return Outcome.SKIPPED
        if (!mutex.tryLock()) return Outcome.BUSY
        try {
            if (automatic && (!automaticEnabled() || store.read() != null)) return Outcome.SKIPPED
            mutableState.value = State(running = true)
            return try {
                replace({
                    if (automatic && !automaticEnabled()) throw Skipped()
                    check(store.write(Phase.PREFLIGHT)) { "Could not record the service update attempt" }
                }, { store.write(Phase.LAUNCHED) })
                store.write(Phase.SUCCEEDED)
                Outcome.UPDATED
            } catch (_: Skipped) {
                Outcome.SKIPPED
            } catch (e: TimeoutCancellationException) {
                store.write(Phase.FAILED, "The replacement service did not connect in time")
                moe.shizuku.manager.utils.Logger.LOGGER.w(e, "Update Porter service timed out")
                Outcome.FAILED
            } catch (e: CancellationException) {
                // A launched update may still finish after the worker is stopped. Reconcile on the next connection.
                throw e
            } catch (e: Exception) {
                store.write(Phase.FAILED, "${e.javaClass.simpleName}: ${e.message}")
                moe.shizuku.manager.utils.Logger.LOGGER.w(e, "Update Porter service")
                Outcome.FAILED
            } finally {
                onFinished()
                mutableState.value = restoredState()
            }
        } finally {
            mutex.unlock()
        }
    }

    suspend fun reconcile(isCurrent: suspend () -> Boolean) {
        if (!mutex.tryLock()) return
        try {
            if (store.read()?.phase?.let { it != Phase.SUCCEEDED } == true && isCurrent()) {
                store.write(Phase.SUCCEEDED)
                mutableState.value = restoredState()
            }
        } finally {
            mutex.unlock()
        }
    }
}
