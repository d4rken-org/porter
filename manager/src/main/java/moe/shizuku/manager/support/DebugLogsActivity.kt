package moe.shizuku.manager.support

import android.os.Bundle
import android.text.format.Formatter
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.shizuku.manager.R
import moe.shizuku.manager.ui.*

class DebugLogsActivity : ComposeActivity() {
    private val model: SupportViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        porterContent {
            val sessions by model.sessions.collectAsStateWithLifecycle()
            val busy by model.busy.collectAsStateWithLifecycle()
            var selected by rememberSaveable { mutableStateOf<String?>(null) }
            var deleting by rememberSaveable { mutableStateOf(false) }
            PorterScaffold(stringResource(R.string.porter_debug_saved), onBack = { finish() }) { padding ->
                LazyColumn(Modifier.padding(padding).consumeWindowInsets(padding)) {
                    if (sessions.isEmpty()) item { Text(stringResource(R.string.porter_debug_empty), Modifier.padding(24.dp)) }
                    items(sessions, key = { it.id }) { session ->
                        SettingsItem(sessionLabel(this@DebugLogsActivity, session.started), R.drawable.ic_terminal_24,
                            if (session.active) stringResource(R.string.porter_debug_recording) else Formatter.formatShortFileSize(this@DebugLogsActivity, session.size),
                            enabled = !busy, onClick = { if (session.active) model.requestRecording() else selected = session.id })
                    }
                }
            }
            selected?.let { id ->
                if (deleting) MessageDialog(stringResource(R.string.porter_debug_delete), stringResource(R.string.porter_debug_delete_message),
                    { deleting = false; selected = null }, stringResource(R.string.porter_debug_delete), onConfirm = { model.delete(id); selected = null; deleting = false })
                else ChoiceDialog(stringResource(R.string.porter_debug_saved), listOf(stringResource(R.string.porter_debug_share), stringResource(R.string.porter_debug_delete)), -1,
                    { selected = null }) { if (it == 0) { model.share(id); selected = null } else deleting = true }
            }
            SupportDialogs(model)
        }
    }
    override fun onResume() { super.onResume(); model.refresh() }
}
