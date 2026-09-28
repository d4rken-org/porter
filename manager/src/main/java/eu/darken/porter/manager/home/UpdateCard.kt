package eu.darken.porter.manager.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.NewReleases
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import eu.darken.porter.manager.R
import eu.darken.porter.manager.updater.AvailableUpdate
import eu.darken.porter.manager.updater.UpdateInstaller.Operation

internal data class UpdateCardState(
    val currentVersion: String,
    val newVersion: String,
    val releaseName: String?,
    val downloadAvailable: Boolean,
    val operation: Operation = Operation.Idle,
)

internal enum class UpdateAction { DOWNLOAD, INSTALL_MANUAL, INSTALL_AUTOMATIC }

/** Tag `v0.7.0-rc0` shows as `0.7.0-rc0`, the form the installed versionName takes. */
internal fun AvailableUpdate.toCardState(operation: Operation = Operation.Idle) = UpdateCardState(
    currentVersion = currentVersion,
    newVersion = release.tag.removePrefix("v"),
    releaseName = release.name?.takeIf { it.isNotBlank() },
    downloadAvailable = release.apk != null,
    operation = operation,
)

@Composable
internal fun UpdateCard(state: UpdateCardState, onIgnore: () -> Unit, onChangelog: () -> Unit, onDownload: () -> Unit) {
    val operation = state.operation
    val idle = operation !is Operation.Working
    HomeCard(stringResource(R.string.updater_card_title), Icons.TwoTone.NewReleases,
        tint = MaterialTheme.colorScheme.onTertiaryContainer,
        containerColor = MaterialTheme.colorScheme.tertiaryContainer) {
        Text(stringResource(R.string.updater_card_versions, state.newVersion, state.currentVersion))
        state.releaseName?.let { Text(it) }
        when (operation) {
            is Operation.Working -> {
                Text(stringResource(if (operation.kind == Operation.Kind.DOWNLOAD) R.string.updater_downloading else R.string.updater_installing))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            is Operation.Saved -> Text(stringResource(R.string.updater_saved, operation.fileName))
            is Operation.Failed -> Text(operation.message, color = MaterialTheme.colorScheme.error)
            Operation.Idle -> Unit
        }
        HomeCardActions {
            TextButton(enabled = idle, onClick = onIgnore) { Text(stringResource(R.string.updater_ignore)) }
            OutlinedButton(enabled = idle, onClick = onChangelog) { Text(stringResource(R.string.updater_changelog)) }
            if (state.downloadAvailable) Button(enabled = idle, onClick = onDownload) { Text(stringResource(R.string.updater_download)) }
        }
    }
}

@Composable
internal fun UpdateDownloadDialog(fileName: String, automaticAvailable: Boolean, onConfirm: (UpdateAction) -> Unit, onDismiss: () -> Unit) {
    var selected by rememberSaveable { mutableStateOf(UpdateAction.DOWNLOAD) }
    val usable = selected != UpdateAction.INSTALL_AUTOMATIC || automaticAvailable
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.updater_dialog_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()).selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(fileName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                UpdateOption(selected == UpdateAction.DOWNLOAD, true, stringResource(R.string.updater_download),
                    stringResource(R.string.updater_download_summary)) { selected = UpdateAction.DOWNLOAD }
                UpdateOption(selected == UpdateAction.INSTALL_MANUAL, true, stringResource(R.string.updater_install_manual),
                    stringResource(R.string.updater_install_manual_summary)) { selected = UpdateAction.INSTALL_MANUAL }
                UpdateOption(selected == UpdateAction.INSTALL_AUTOMATIC, automaticAvailable, stringResource(R.string.updater_install_automatic),
                    stringResource(if (automaticAvailable) R.string.updater_install_automatic_summary else R.string.updater_install_automatic_requirement)) {
                    selected = UpdateAction.INSTALL_AUTOMATIC
                }
            }
        },
        confirmButton = { TextButton(enabled = usable, onClick = { onConfirm(selected) }) { Text(stringResource(R.string.updater_continue)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } })
}

@Composable
private fun UpdateOption(selected: Boolean, enabled: Boolean, title: String, summary: String, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect).padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = 12.dp).alpha(if (enabled) 1f else 0.6f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
