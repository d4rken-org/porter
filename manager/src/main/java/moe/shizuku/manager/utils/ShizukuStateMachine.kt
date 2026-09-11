package moe.shizuku.manager.utils

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import rikka.shizuku.Shizuku

object ShizukuStateMachine {

    enum class State { STARTING, RUNNING, STOPPING, STOPPED, CRASHED }

    private var state = AtomicReference<State>(State.STOPPED)
    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()

    private val lock = Any()

    /**
     * Guarded by [lock]. Each entry is a transition and the listeners registered when it was
     * enqueued. Appends happen under the same [lock] that stores the new state, and [drain] removes
     * from the front, so the deque's FIFO order is the delivery order.
     */
    private val pending = ArrayDeque<Pair<State, List<(State) -> Unit>>>()

    /** Guarded by [lock]. True while some frame is running [drain]. */
    private var draining = false

    init {
        Shizuku.addBinderReceivedListenerSticky(
            Shizuku.OnBinderReceivedListener { set(State.RUNNING) }
        )
        Shizuku.addBinderDeadListener(
            Shizuku.OnBinderDeadListener { setDead() }
        )
    }

    fun get(): State = state.get()

    private fun transition(transform: (State) -> State) {
        synchronized(lock) {
            val oldState = state.get()
            val newState = transform(oldState)
            if (oldState == newState) return
            state.set(newState)
            pending.addLast(newState to listeners.toList())
            // A transition raised from inside a listener lands here while an outer frame is still
            // delivering; that frame picks this entry up and hands it to the listeners registered
            // at this point only, so a listener registered later sees the state it was given at
            // registration and the transitions after it, never this one.
            if (draining) return
            draining = true
        }
        drain()
    }

    /** Notifies outside [lock]: listener bodies reach a root shell and the main thread. */
    private fun drain() {
        while (true) {
            val (newState, recipients) = synchronized(lock) {
                pending.removeFirstOrNull().also { if (it == null) draining = false }
            } ?: return
            recipients.forEach { listener ->
                if (listeners.contains(listener)) listener(newState)
            }
            Log.d("ShizukuStateMachine", newState.toString())
        }
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
        val state = if (Shizuku.pingBinder()) State.RUNNING else State.STOPPED
        set(state)
        return state
    }

    fun isRunning(): Boolean {
        return get() == State.RUNNING
    }

    fun isDead(): Boolean {
        return (get() == State.STOPPED || get() == State.CRASHED) 
    }

    fun addListener(listener: (State) -> Unit) {
        val current = synchronized(lock) {
            listeners.add(listener)
            state.get()
        }
        listener(current)
    }

    fun removeListener(listener: (State) -> Unit) {
        listeners.remove(listener)
    }

    fun asFlow(): Flow<State> = callbackFlow {
        val listener: (State) -> Unit = { trySend(it).isSuccess }
        addListener(listener)
        awaitClose { removeListener(listener) }
    }

}