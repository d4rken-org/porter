package moe.shizuku.manager.management

import android.os.Bundle
import eu.darken.porter.common.DiscoveredApplication
import java.text.DateFormat
import java.util.Date
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.shizuku.manager.Helps
import moe.shizuku.manager.R
import moe.shizuku.manager.ui.*
import moe.shizuku.manager.utils.ShizukuStateMachine

class ApplicationManagementActivity : ComposeActivity() {
    override val protectTouches = true
    private val model: AppsViewModel by viewModels()
    @OptIn(ExperimentalLayoutApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ShizukuStateMachine.isRunning()) { finish(); return }
        porterContent {
            val state by model.state.collectAsStateWithLifecycle()
            val service by remember { ShizukuStateMachine.asFlow() }.collectAsStateWithLifecycle(ShizukuStateMachine.get())
            LaunchedEffect(service) { if (service != ShizukuStateMachine.State.RUNNING) finish() }
            PorterScaffold(stringResource(R.string.home_app_management_title), onBack = { finish() }) { padding ->
                LazyColumn(Modifier.padding(padding).consumeWindowInsets(padding)) {
                    if (state.apps.any { it.canToggle }) item {
                        SettingsSwitch(stringResource(R.string.app_management_toggle_all), R.drawable.ic_apps_outline_24,
                            state.allGranted, enabled = !state.loading, onCheckedChange = model::toggleAll)
                    }
                    if (state.legacy) item { Text(stringResource(R.string.porter_discovery_legacy), Modifier.padding(16.dp)) }
                    if (state.failedUsers.isNotEmpty()) item { Text(stringResource(R.string.porter_discovery_partial), Modifier.padding(16.dp)) }
                    if (state.apps.isNotEmpty()) item { Text(stringResource(R.string.porter_connections_explanation), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall) }
                    if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    if (!state.loading && state.apps.isEmpty()) item { Text(stringResource(R.string.home_app_management_empty), Modifier.padding(24.dp)) }
                    items(state.apps, key = { "${it.uid}:${it.packageName}" }) { app ->
                        Row(Modifier.fillMaxWidth().toggleable(value = app.granted, enabled = !state.loading && app.canToggle, role = Role.Switch, onValueChange = { model.toggle(app, it) })
                            .padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            app.icon?.let { Image(it.asImageBitmap(), null, Modifier.size(40.dp)) }
                            Column(Modifier.weight(1f)) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(app.label, Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.bodyLarge)
                                    listOf(DiscoveredApplication.API_PORTER to R.string.porter_api_badge_porter,
                                        DiscoveredApplication.API_SHIZUKU to R.string.porter_api_badge_shizuku).forEach { (api, label) ->
                                        if (app.declaredApis and api != 0) {
                                            Surface(Modifier.align(Alignment.CenterVertically), shape = RoundedCornerShape(50),
                                                color = MaterialTheme.colorScheme.secondaryContainer,
                                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
                                                Text(stringResource(label), Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                                    style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                }
                                Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                                val connection = when (app.connectionStatus) {
                                    DiscoveredApplication.DIRECT, DiscoveredApplication.COMPANION -> null
                                    DiscoveredApplication.NEEDS_COMPANION -> R.string.porter_connection_needs_companion
                                    DiscoveredApplication.UNSUPPORTED -> R.string.porter_connection_unsupported
                                    DiscoveredApplication.MANAGED_ONLY -> R.string.porter_connection_managed
                                    else -> R.string.porter_connection_unknown
                                }
                                connection?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
                                if (app.authorization == DiscoveredApplication.DENIED) {
                                    Text(stringResource(R.string.porter_access_blocked), style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error)
                                }
                                Text(if (app.lastConnectedAt == null) stringResource(R.string.porter_connection_never_recorded)
                                    else stringResource(R.string.porter_connection_last, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(app.lastConnectedAt))),
                                    style = MaterialTheme.typography.bodySmall)
                                if (app.requiresRoot) Text(stringResource(R.string.app_management_item_summary_requires_root), style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(app.granted, null, enabled = !state.loading && app.canToggle)
                        }
                    }
                }
            }
            state.error?.let { error ->
                AlertDialog(onDismissRequest = model::clearError, title = { Text(stringResource(R.string.porter_support_error)) }, text = {
                    if (error is SecurityException) HtmlText(stringResource(R.string.app_management_dialog_adb_is_limited_message, Helps.ADB_PERMISSION.get()))
                    else Text(error.localizedMessage ?: error.javaClass.simpleName)
                }, confirmButton = { TextButton(onClick = model::clearError) { Text(stringResource(android.R.string.ok)) } })
            }
        }
    }
    override fun onResume() { super.onResume(); if (ShizukuStateMachine.isRunning()) model.load() }
}
