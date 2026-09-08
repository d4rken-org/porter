package moe.shizuku.manager.support

import android.app.Application
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

internal class ContactViewModel(application: Application, savedState: SavedStateHandle) : SupportViewModel(application, savedState) {
    val category = savedState.getStateFlow("category", 0)
    val selectedId = savedState.getStateFlow<String?>("attachment", null)
    val validated = savedState.getStateFlow("validated", false)
    override fun onSessions(sessions: List<DebugLogStore.Session>) {
        val active = sessions.firstOrNull { it.active }
        if (active != null) savedState["recorded"] = active.id
        else savedState.get<String>("recorded")?.let { id ->
            if (sessions.any { it.id == id }) savedState["attachment"] = id
            savedState["recorded"] = null
        }
    }
    fun category(value: Int) { savedState["category"] = value }
    fun attachment(id: String?) { savedState["attachment"] = id }
    fun send(descriptionInput: String, expectedInput: String) {
        savedState["validated"] = true
        val description = descriptionInput.trim()
        val expected = expectedInput.trim()
        val bug = category.value == 0
        if (description.length < 20 || (bug && expected.isEmpty()) || recording.value.active) return
        val category = arrayOf("Bug", "Question", "Feature request")[category.value]
        val attachment = selectedId.value
        operation {
            val context = getApplication<Application>()
            val uri = attachment?.let { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", DebugRecorder.export(context, it)) }
            events.send(contactIntent(context, category, description, expected, bug, uri))
        }
    }
}
