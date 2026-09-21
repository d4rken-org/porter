package eu.darken.porter.manager.utils

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import eu.darken.porter.manager.ServerBinder

class PorterStateMachine {

    enum class State { STARTING, RUNNING, STOPPING, STOPPED, CRASHED }

    private val lock = Any()

    /** The authoritative store. Written only under [lock], read without it by [get]. */
    @Volatile
    private var current: State = State.STOPPED

    /**
     * Seeded at declaration so a subscriber that arrives before any transition still gets the
     * current state. `DROP_OLDEST` keeps [current] and the replay slot in agreement: an emit that
     * could fail would strand the flow on a stale state, and the no-op dedup in [transition] means
     * no later `set` of that same state would ever repair it. The cost is that a subscriber more
     * than `1 + extraBufferCapacity` transitions behind loses its own oldest unconsumed values;
     * the newest transition is never the one dropped.
     */
    private val transitions = MutableSharedFlow<State>(
        replay = 1,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    ).also { it.tryEmit(State.STOPPED) }

    private val attached = AtomicBoolean(false)

    /**
     * Main, and never immediate: the SDK publishes under its own lock, and a collector that ran
     * inline would carry [transition] and every subscriber of [asFlow] into that lock.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Starts following the delivered server binder, which is there whether or not the SDK can
     * speak to that server: a server the manager has to stop or replace is a running one.
     * Explicit, and separate from construction: a second collector would double every transition,
     * and merely holding an instance must not wire anything up.
     */
    fun attachToPorter() {
        if (!attached.compareAndSet(false, true)) return
        scope.launch {
            ServerBinder.binder.collect { binder ->
                if (binder != null) set(State.RUNNING) else setDead()
            }
        }
    }

    fun get(): State = current

    private fun transition(transform: (State) -> State) {
        val newState = synchronized(lock) {
            val oldState = current
            val newState = transform(oldState)
            if (oldState == newState) return
            current = newState
            // Under [lock], so buffer order is store order.
            transitions.tryEmit(newState)
            newState
        }
        Log.d("PorterStateMachine", newState.toString())
    }

    fun set(newState: State) = transition { newState }

    fun setDead() = transition {
        when (it) {
            State.RUNNING -> State.CRASHED
            State.STOPPING -> State.STOPPED
            else -> it
        }
    }

    fun update(): State {
        val state = if (ServerBinder.isAlive) State.RUNNING else State.STOPPED
        set(state)
        return state
    }

    fun isRunning(): Boolean {
        return get() == State.RUNNING
    }

    fun isDead(): Boolean {
        return (get() == State.STOPPED || get() == State.CRASHED)
    }

    fun asFlow(): Flow<State> = transitions.asSharedFlow()

    companion object {
        val instance: PorterStateMachine by lazy { PorterStateMachine() }
    }

}
