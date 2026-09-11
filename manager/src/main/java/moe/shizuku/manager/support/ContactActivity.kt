package moe.shizuku.manager.support

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.HelpOutline
import androidx.compose.material.icons.twotone.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.shizuku.manager.R
import moe.shizuku.manager.ui.*

class ContactActivity : ComposeActivity() {
    private val model: ContactViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        porterContent {
            val category by model.category.collectAsStateWithLifecycle()
            var description by rememberSaveable { mutableStateOf("") }
            var expected by rememberSaveable { mutableStateOf("") }
            val attachment by model.selectedId.collectAsStateWithLifecycle()
            val sessions by model.sessions.collectAsStateWithLifecycle()
            val recording by model.recording.collectAsStateWithLifecycle()
            val busy by model.busy.collectAsStateWithLifecycle()
            val validated by model.validated.collectAsStateWithLifecycle()
            var dialog by rememberSaveable { mutableStateOf<String?>(null) }
            val categories = stringArrayResource(R.array.porter_contact_categories).toList()
            val saved = sessions.filterNot { it.active }
            val attachmentLabels = listOf(stringResource(R.string.porter_contact_no_attachment)) + saved.map { sessionLabel(this, it.started) }
            val attachmentIds = listOf(null) + saved.map { it.id }
            val badDescription = validated && description.trim().length < 20
            val badExpected = validated && category == 0 && expected.isBlank()
            PorterScaffold(stringResource(R.string.porter_contact), onBack = { finish() }) { padding ->
                Column(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.porter_contact_summary))
                    SettingsItem(stringResource(R.string.porter_contact_category), Icons.AutoMirrored.TwoTone.HelpOutline, categories[category], enabled = !busy, onClick = { dialog = "category" })
                    OutlinedTextField(description, { description = it.take(5000) }, Modifier.fillMaxWidth(), enabled = !busy,
                        label = { Text(stringResource(R.string.porter_contact_description)) }, minLines = 4, isError = badDescription,
                        supportingText = { if (badDescription) Text(stringResource(R.string.porter_contact_description_error)) })
                    if (category == 0) OutlinedTextField(expected, { expected = it.take(3000) }, Modifier.fillMaxWidth(), enabled = !busy,
                        label = { Text(stringResource(R.string.porter_contact_expected)) }, minLines = 2, isError = badExpected,
                        supportingText = { if (badExpected) Text(stringResource(R.string.porter_contact_expected_error)) })
                    SettingsItem(stringResource(R.string.porter_contact_attachment), Icons.TwoTone.Terminal,
                        attachmentLabels.getOrElse(attachmentIds.indexOf(attachment)) { stringResource(R.string.porter_contact_no_attachment) },
                        enabled = !busy && !recording.active, onClick = { dialog = "attachment" })
                    OutlinedButton(onClick = model::requestRecording, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(if (recording.active) R.string.porter_debug_stop else R.string.porter_debug_start))
                    }
                    Text(stringResource(R.string.porter_contact_privacy), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { model.send(description, expected) }, enabled = !recording.active && !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.porter_contact_send)) }
                }
            }
            when (dialog) {
                "category" -> ChoiceDialog(stringResource(R.string.porter_contact_category), categories, category, { dialog = null }) { model.category(it); dialog = null }
                "attachment" -> ChoiceDialog(stringResource(R.string.porter_contact_attachment), attachmentLabels, attachmentIds.indexOf(attachment), { dialog = null }) { model.attachment(attachmentIds[it]); dialog = null }
            }
            SupportDialogs(model)
        }
    }
    override fun onResume() { super.onResume(); model.refresh() }
}
