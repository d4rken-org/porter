package moe.shizuku.manager.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Adb
import androidx.compose.material.icons.twotone.Apps
import androidx.compose.material.icons.twotone.Info
import androidx.compose.material.icons.twotone.Settings
import androidx.compose.material.icons.twotone.Tag
import androidx.compose.material.icons.twotone.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.shizuku.manager.Helps
import moe.shizuku.manager.R
import moe.shizuku.manager.compatibility.CompatibilityRepository
import moe.shizuku.manager.management.AppsViewModel
import moe.shizuku.manager.ui.HtmlText
import moe.shizuku.manager.ui.PorterScaffold

internal data class HomeUiState(
    val statusUi: ServiceStatusUi,
    val appsState: AppsViewModel.State,
    val compatState: CompatibilityRepository.State,
    val buildBadge: String?,
    val showBatteryCard: Boolean,
    val canStart: Boolean = false,
    val primaryUser: Boolean = true,
    val busy: Boolean = false,
    val wirelessAdbAvailable: Boolean = false,
    val tlsSupported: Boolean = false,
)

internal data class HomeActions(
    val onOpenSettings: () -> Unit,
    val onOpenApps: () -> Unit,
    val onOpenCompatibility: () -> Unit,
    val onOpenService: () -> Unit,
    val onStartRoot: () -> Unit,
    val onPairWireless: () -> Unit,
    val onStartWireless: () -> Unit,
    val onViewWirelessGuide: () -> Unit,
    val onShowAdbCommand: () -> Unit,
)

@Composable
internal fun HomeScreenContent(state: HomeUiState, actions: HomeActions, modifier: Modifier = Modifier,
                               listState: LazyListState = rememberLazyListState()) {
    val resources = LocalContext.current.resources
    val apps = state.appsState
    val compat = state.compatState
    val running = state.statusUi.running
    val countsAvailable = running && !apps.loading && apps.error == null
    val discoveryAvailable = countsAvailable && !apps.legacy && apps.failedUsers.isEmpty()
    PorterScaffold(stringResource(R.string.app_name), subtitle = stringResource(R.string.porter_home_subtitle),
        titleIcon = homeTitleIcon(running, state.statusUi.restricted), titleBadge = state.buildBadge, actions = {
            IconButton(onClick = actions.onOpenSettings) {
                Icon(Icons.TwoTone.Settings, stringResource(R.string.settings_title))
            }
        }) { padding ->
        LazyColumn(modifier.padding(padding).consumeWindowInsets(padding), state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { ServiceStatusCard(state.statusUi, onDetails = actions.onOpenService) }
            if (state.canStart) {
                if (state.wirelessAdbAvailable) item {
                    HomeCard(stringResource(R.string.home_wireless_adb_title), Icons.TwoTone.Wifi) {
                        HtmlText(stringResource(if (state.tlsSupported) R.string.home_wireless_adb_description else R.string.home_wireless_adb_description_pre_11))
                        HomeCardActions {
                            if (state.tlsSupported) TextButton(onClick = actions.onViewWirelessGuide) { Text(stringResource(R.string.home_wireless_adb_view_guide_button)) }
                            if (state.tlsSupported) OutlinedButton(onClick = actions.onPairWireless) { Text(stringResource(R.string.adb_pairing)) }
                            Button(onClick = actions.onStartWireless) { Text(stringResource(R.string.home_root_button_start)) }
                        }
                    }
                }
                item {
                    HomeCard(stringResource(R.string.home_adb_title), Icons.TwoTone.Adb) {
                        HtmlText(stringResource(R.string.home_adb_description, Helps.ADB.get()))
                        HomeCardActions { OutlinedButton(onClick = actions.onShowAdbCommand) { Text(stringResource(R.string.home_adb_button_view_command)) } }
                    }
                }
                item {
                    HomeCard(stringResource(R.string.home_root_title), Icons.TwoTone.Tag) {
                        Text(stringResource(R.string.porter_root_requirement))
                        HomeCardActions { OutlinedButton(onClick = actions.onStartRoot) { Text(stringResource(R.string.home_root_button_start)) } }
                    }
                }
            }
            if (!running && !state.busy && !state.primaryUser) item {
                HomeCard(stringResource(R.string.porter_primary_user_title), Icons.TwoTone.Info) {
                    Text(stringResource(R.string.porter_primary_user_description))
                }
            }
            item {
                HomeCard(stringResource(R.string.porter_applications), Icons.TwoTone.Apps, actions.onOpenApps,
                    subtitle = when {
                        !running -> stringResource(R.string.porter_apps_service_unavailable)
                        !countsAvailable -> stringResource(R.string.porter_apps_counts_unavailable)
                        !discoveryAvailable -> resources.getQuantityString(R.plurals.home_app_management_authorized_apps_count, apps.grantedCount, apps.grantedCount)
                        else -> stringResource(R.string.porter_apps_counts, apps.grantedCount, apps.compatibleCount)
                    }, actionLabel = stringResource(R.string.porter_manage_apps_action)) {
                    if (countsAvailable && apps.effectiveAccessEnabled == false) Text(stringResource(R.string.porter_access_paused),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    if (countsAvailable && apps.failedUsers.isNotEmpty()) Text(stringResource(R.string.porter_discovery_partial), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (compat.isCompanion) item {
                InstalledCompatibilityCard(
                    compatibilityVersionText(compat.installedVersionName, compat.installedVersionCode),
                    compatibilityUsageText(running, apps),
                    notice = when (compat.status) {
                        CompatibilityRepository.Status.UPDATE -> stringResource(R.string.compat_status_update)
                        CompatibilityRepository.Status.INVALID -> stringResource(R.string.compat_status_invalid)
                        else -> null
                    }, onDetails = actions.onOpenCompatibility)
            } else if (countsAvailable && (apps.companionRequiredCount > 0 || apps.pendingCompanionCount > 0)) item {
                CompatibilityCard(stringResource(when (compat.status) {
                    CompatibilityRepository.Status.UPDATE -> R.string.compat_status_update
                    CompatibilityRepository.Status.CONFLICT -> R.string.compat_status_conflict
                    CompatibilityRepository.Status.INVALID -> R.string.compat_status_invalid
                    else -> R.string.compat_card_description
                }), onDownload = actions.onOpenCompatibility, affectedApps = resources.getQuantityString(
                    R.plurals.porter_apps_need_companion, apps.companionRequiredCount, apps.companionRequiredCount))
            }
            if (state.showBatteryCard) item {
                HomeCard(stringResource(R.string.porter_background_operation), Icons.TwoTone.Info,
                    actions.onOpenSettings, actionLabel = stringResource(R.string.settings_title)) {
                    Text(stringResource(R.string.snackbar_battery_optimization_home), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
