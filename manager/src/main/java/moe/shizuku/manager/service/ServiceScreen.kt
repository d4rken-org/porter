package moe.shizuku.manager.service

import android.text.BidiFormatter
import android.text.TextDirectionHeuristics
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Info
import androidx.compose.material.icons.twotone.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import moe.shizuku.manager.Helps
import moe.shizuku.manager.R
import moe.shizuku.manager.home.HomeCard
import moe.shizuku.manager.home.serviceStatusUi
import moe.shizuku.manager.home.statusIcon
import moe.shizuku.manager.home.statusTint
import moe.shizuku.manager.model.PorterServiceVersion
import moe.shizuku.manager.ui.HtmlText
import moe.shizuku.manager.ui.PorterScaffold

internal data class ServiceActions(
    val onBack: () -> Unit,
    val onUpdate: () -> Unit,
    val onStop: () -> Unit,
)

@Composable
internal fun serviceVersionText(version: PorterServiceVersion?): String {
    if (version == null) return stringResource(R.string.porter_build_identity_unavailable)
    val bidi = BidiFormatter.getInstance(LocalLayoutDirection.current == LayoutDirection.Rtl)
    fun wrap(value: String) = bidi.unicodeWrap(value, TextDirectionHeuristics.LTR)
    val id = version.buildId?.takeIf { it.isNotBlank() }
    val hash = id?.substringBeforeLast(':', id)
    val shortId = hash?.let { if (it.length > 12) it.take(12) + "…" else it }
    val type = id?.substringAfterLast(':', "")?.takeIf { it.isNotBlank() }
    return listOf(
        stringResource(R.string.porter_version_compact, wrap(version.name), version.code),
        if (id == null) stringResource(R.string.porter_build_identity_unavailable)
        else stringResource(R.string.porter_build_compact, wrap(shortId!!),
            type?.let(::wrap) ?: stringResource(R.string.porter_status_version_unknown)),
    ).joinToString("\n")
}

@Composable
internal fun ServiceScreenContent(
    snapshot: ServiceSnapshot,
    actions: ServiceActions,
    modifier: Modifier = Modifier,
) {
    val ui = serviceStatusUi(snapshot)
    val installedDetails = serviceVersionText(snapshot.installed)
    val runningDetails = serviceVersionText(snapshot.status.porterVersion)
    val apiDetails = stringResource(R.string.porter_service_api_value, snapshot.status.apiVersion, snapshot.status.patchVersion)
    val processDetails = stringResource(R.string.porter_service_process_value, snapshot.status.uid,
        snapshot.status.pid?.toString() ?: stringResource(R.string.porter_status_version_unknown))
    PorterScaffold(stringResource(R.string.porter_service_title), onBack = actions.onBack) { padding ->
        LazyColumn(modifier.padding(padding).consumeWindowInsets(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                HomeCard(ui.title, ui.statusIcon, tint = ui.statusTint(), subtitle = ui.subtitle) {
                    when {
                        snapshot.busy -> {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(stringResource(R.string.porter_service_progress_background), style = MaterialTheme.typography.bodyMedium)
                        }
                        snapshot.failed -> {
                            Text(stringResource(R.string.porter_service_update_failed), color = MaterialTheme.colorScheme.error)
                            Text(stringResource(if (snapshot.running) R.string.porter_service_failed_running else R.string.porter_service_failed_stopped))
                        }
                        snapshot.updateAvailable -> {
                            Text(stringResource(R.string.porter_service_update_available), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(if (snapshot.buildRelation == ServiceSnapshot.BuildRelation.UNAVAILABLE)
                                R.string.porter_service_build_unknown_summary else R.string.porter_service_build_different_summary))
                        }
                        snapshot.running && !snapshot.restricted -> Text(stringResource(R.string.porter_service_healthy))
                    }
                    if (snapshot.updateAvailable && !snapshot.primaryUser && !snapshot.busy) {
                        Text(stringResource(R.string.porter_service_update_primary))
                    }
                    if (snapshot.canStop || snapshot.canUpdate) ServiceActionRow(
                        start = {
                            if (snapshot.canStop) OutlinedButton(onClick = actions.onStop) {
                                Text(stringResource(R.string.action_stop), color = MaterialTheme.colorScheme.error)
                            }
                        },
                        end = {
                            if (snapshot.canUpdate) Button(onClick = actions.onUpdate) {
                                Text(stringResource(if (snapshot.failed) R.string.porter_service_update_retry else R.string.porter_service_update_action))
                            }
                        },
                    )
                }
            }
            if (snapshot.restricted) item {
                HomeCard(stringResource(R.string.app_management_dialog_adb_is_limited_title), Icons.TwoTone.Warning,
                    tint = colorResource(R.color.porter_status_warning)) {
                    HtmlText(stringResource(R.string.app_management_dialog_adb_is_limited_message, Helps.ADB_PERMISSION.get()))
                }
            }
            if (!snapshot.running && !snapshot.busy && !snapshot.primaryUser) item {
                HomeCard(stringResource(R.string.porter_primary_user_title), Icons.TwoTone.Info) {
                    Text(stringResource(R.string.porter_primary_user_description))
                }
            }
            item {
                HomeCard(stringResource(R.string.porter_installed_app), Icons.TwoTone.Info) {
                    Text(installedDetails, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (snapshot.running) item {
                HomeCard(stringResource(R.string.porter_running_service), ui.statusIcon, tint = ui.statusTint()) {
                    Text(runningDetails, style = MaterialTheme.typography.bodyMedium)
                    Text(apiDetails, style = MaterialTheme.typography.bodySmall)
                    Text(processDetails, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
internal fun StopServiceDialog(secondaryUser: Boolean, onDismiss: () -> Unit, onStop: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.action_stop)) },
        text = { Text(stringResource(if (secondaryUser) R.string.porter_secondary_user_stop_message else R.string.porter_status_stop_message)) },
        confirmButton = { TextButton(onClick = onStop) { Text(stringResource(R.string.action_stop), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } })
}

@Composable
internal fun ServiceActionRow(start: @Composable () -> Unit, end: @Composable () -> Unit, modifier: Modifier = Modifier) {
    Layout(contents = listOf(start, end), modifier = modifier.fillMaxWidth()) { (startSlot, endSlot), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val startAction = startSlot.firstOrNull()?.measure(loose)
        val endAction = endSlot.firstOrNull()?.measure(loose)
        val gap = if (startAction != null && endAction != null) 8.dp.roundToPx() else 0
        val width = constraints.maxWidth
        val singleRow = (startAction?.width ?: 0) + gap + (endAction?.width ?: 0) <= width
        val rowHeight = maxOf(startAction?.height ?: 0, endAction?.height ?: 0)
        val height = if (singleRow) rowHeight else (startAction?.height ?: 0) + gap + (endAction?.height ?: 0)
        layout(width, height) {
            startAction?.placeRelative(0, if (singleRow) (rowHeight - startAction.height) / 2 else 0)
            endAction?.placeRelative(width - endAction.width,
                if (singleRow) (rowHeight - endAction.height) / 2 else (startAction?.height ?: 0) + gap)
        }
    }
}

@Composable
internal fun UpdateServiceDialog(retry: Boolean, onDismiss: () -> Unit, onUpdate: () -> Unit) {
    val title = stringResource(if (retry) R.string.porter_service_update_retry else R.string.porter_service_update_action)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { Text(stringResource(R.string.porter_service_update_summary)) },
        confirmButton = { TextButton(onClick = onUpdate) { Text(title) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } })
}
