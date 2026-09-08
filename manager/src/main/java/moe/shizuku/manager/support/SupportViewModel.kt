package moe.shizuku.manager.support

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

internal open class SupportViewModel(application: Application, protected val savedState: SavedStateHandle) : AndroidViewModel(application) {
    val recording = DebugRecorder.state
    private val mutableSessions = MutableStateFlow<List<DebugLogStore.Session>>(emptyList())
    val sessions = mutableSessions.asStateFlow()
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val consent = savedState.getStateFlow<Boolean?>("recordingConsent", null)
    val events = Channel<Intent>(Channel.BUFFERED)
    init { viewModelScope.launch { recording.collect { refresh() } } }

    fun refresh() = viewModelScope.launch {
        try { updateSessions() }
        catch (e: Exception) { report(e) }
    }
    private suspend fun updateSessions() {
        val loaded = DebugRecorder.sessions(getApplication())
        mutableSessions.value = loaded
        onSessions(loaded)
    }
    protected open fun onSessions(sessions: List<DebugLogStore.Session>) = Unit
    fun requestRecording() { if (!busy.value) savedState["recordingConsent"] = recording.value.active }
    fun dismissRecording() { if (!busy.value) savedState["recordingConsent"] = null }
    fun performRecording(stop: Boolean) = operation {
        if (stop) DebugRecorder.stop(getApplication()) else DebugRecorder.start(getApplication())
        savedState["recordingConsent"] = null
        updateSessions()
    }
    fun delete(id: String) = operation {
        DebugRecorder.delete(getApplication(), id)
        updateSessions()
    }
    fun share(id: String) = operation {
        events.send(logShareIntent(getApplication(), DebugRecorder.export(getApplication(), id)))
    }
    protected fun operation(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try { block() } catch (e: Exception) { report(e) } finally { busy.value = false }
        }
    }
    fun report(e: Exception) {
        if (e is CancellationException) throw e
        error.value = e.localizedMessage ?: e.javaClass.simpleName
    }
}
