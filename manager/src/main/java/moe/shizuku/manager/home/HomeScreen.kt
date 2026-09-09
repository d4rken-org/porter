package moe.shizuku.manager.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.Helps
import moe.shizuku.manager.R
import moe.shizuku.manager.compatibility.CompatibilityRepository
import moe.shizuku.manager.management.AppsViewModel
import moe.shizuku.manager.ui.HtmlText
import moe.shizuku.manager.ui.PorterScaffold
import moe.shizuku.manager.utils.ShizukuStateMachine

/** Everything [HomeScreenContent] branches on, resolved by the activity. */
internal data class HomeUiState(
    val statusUi: ServiceStatusUi,
    val permitted: Boolean,
    val appsState: AppsViewModel.State,
    val compatState: CompatibilityRepository.State,
    val buildBadge: String?,
    val showBatteryCard: Boolean,
    val isSecondaryUser: Boolean,
    val isRooted: Boolean,
    val wirelessAdbAvailable: Boolean,
    val tlsSupported: Boolean,
    val serviceState: ShizukuStateMachine.State,
)

internal data class HomeActions(
    val onOpenSettings: () -> Unit,
    val onOpenApps: () -> Unit,
    val onOpenCompatibility: () -> Unit,
    val onShowStatusDetails: () -> Unit,
    val onFixBatteryOptimization: () -> Unit,
    val onStartRoot: () -> Unit,
    val onPairWireless: () -> Unit,
    val onStartWireless: () -> Unit,
    val onViewWirelessGuide: () -> Unit,
    val onShowAdbCommand: () -> Unit,
)

@Composable
internal fun HomeScreenContent(state: HomeUiState, actions: HomeActions, modifier: Modifier = Modifier) {
    val resources = LocalContext.current.resources
    val statusUi = state.statusUi
    val appsState = state.appsState
    val compatState = state.compatState
    val running = statusUi.running
    val restricted = statusUi.restricted
    PorterScaffold(stringResource(R.string.app_name), subtitle = stringResource(R.string.porter_home_subtitle),
        titleIcon = R.drawable.porter_mascot, titleBadge = state.buildBadge, actions = {
        IconButton(onClick = actions.onOpenSettings) {
            Icon(painterResource(R.drawable.ic_action_settings_24dp), stringResource(R.string.settings_title))
        }
    }) { padding ->
        LazyColumn(modifier.padding(padding).consumeWindowInsets(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { ServiceStatusCard(statusUi, accessPaused = appsState.accessEnabled == false, onDetails = actions.onShowStatusDetails) }
            if (running && state.permitted) item {
                val count = appsState.grantedCount
                HomeCard(stringResource(R.string.porter_applications), R.drawable.ic_apps_outline_24,
                    actions.onOpenApps,
                    subtitle = if (appsState.legacy || appsState.failedUsers.isNotEmpty())
                        resources.getQuantityString(R.plurals.home_app_management_authorized_apps_count, count, count)
                    else stringResource(R.string.porter_apps_counts, count, appsState.compatibleCount),
                    actionLabel = stringResource(R.string.porter_manage_apps_action)) {
                    if (appsState.companionRequiredCount > 0) Text(
                        resources.getQuantityString(R.plurals.porter_apps_need_companion, appsState.companionRequiredCount, appsState.companionRequiredCount),
                        style = MaterialTheme.typography.bodyMedium)
                    if (appsState.failedUsers.isNotEmpty()) Text(stringResource(R.string.porter_discovery_partial), style = MaterialTheme.typography.bodySmall)
                    if (appsState.accessEnabled == false) Text(stringResource(R.string.porter_access_paused),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }
            if (compatState.isCompanion) item {
                InstalledCompatibilityCard(
                    compatibilityVersionText(compatState.installedVersionName, compatState.installedVersionCode),
                    compatibilityUsageText(running, appsState),
                    notice = when (compatState.status) {
                        CompatibilityRepository.Status.UPDATE -> stringResource(R.string.compat_status_update)
                        CompatibilityRepository.Status.INVALID -> stringResource(R.string.compat_status_invalid)
                        else -> null
                    },
                    onDetails = actions.onOpenCompatibility
                )
            } else if ((BuildConfig.IS_FOSS && (compatState.status == CompatibilityRepository.Status.UPDATE
                    || compatState.status == CompatibilityRepository.Status.INVALID
                    || (compatState.status != CompatibilityRepository.Status.INSTALLED && appsState.companionRequiredCount > 0)))
                || (running && state.permitted && appsState.pendingCompanionCount > 0)) item {
                CompatibilityCard(stringResource(when (compatState.status) {
                    CompatibilityRepository.Status.UPDATE -> R.string.compat_status_update
                    CompatibilityRepository.Status.CONFLICT -> R.string.compat_status_conflict
                    CompatibilityRepository.Status.INVALID -> R.string.compat_status_invalid
                    else -> R.string.compat_card_description
                }), onDownload = actions.onOpenCompatibility)
            }
            if (restricted) item {
                HomeCard(stringResource(R.string.app_management_dialog_adb_is_limited_title), R.drawable.ic_warning_24) {
                    HtmlText(stringResource(R.string.app_management_dialog_adb_is_limited_message, Helps.ADB_PERMISSION.get()))
                }
            }
            if (state.showBatteryCard) item {
                HomeCard(stringResource(R.string.snackbar_battery_optimization_home), R.drawable.ic_outline_info_24) {
                    HomeCardActions {
                        TextButton(onClick = actions.onFixBatteryOptimization) { Text(stringResource(R.string.snackbar_action_fix)) }
                    }
                }
            }
            if (!running && state.isSecondaryUser) item {
                HomeCard(stringResource(R.string.porter_primary_user_title), R.drawable.ic_outline_info_24) {
                    Text(stringResource(R.string.porter_primary_user_description), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (!running && !state.isSecondaryUser) {
                if (state.isRooted) item {
                    HomeCard(stringResource(R.string.home_root_title), R.drawable.ic_root_24dp) {
                        HtmlText(stringResource(R.string.home_root_description, "<a href=\"${Helps.SUI.get()}\">Sui</a>", "Sui"))
                        HomeCardActions {
                            Button(onClick = actions.onStartRoot) { Text(stringResource(R.string.home_root_button_start)) }
                        }
                    }
                }
                if (state.wirelessAdbAvailable) item {
                    HomeCard(stringResource(R.string.home_wireless_adb_title), R.drawable.ic_wadb_24) {
                        HtmlText(stringResource(if (state.tlsSupported) R.string.home_wireless_adb_description else R.string.home_wireless_adb_description_pre_11))
                        if (state.tlsSupported) {
                            HomeCardActions {
                                TextButton(onClick = actions.onViewWirelessGuide) { Text(stringResource(R.string.home_wireless_adb_view_guide_button)) }
                            }
                        }
                        HomeCardActions {
                            if (state.tlsSupported) {
                                OutlinedButton(onClick = actions.onPairWireless) { Text(stringResource(R.string.adb_pairing)) }
                            }
                            Button(onClick = actions.onStartWireless, enabled = state.serviceState != ShizukuStateMachine.State.STARTING) { Text(stringResource(R.string.home_root_button_start)) }
                        }
                    }
                }
                item {
                    HomeCard(stringResource(R.string.home_adb_title), R.drawable.ic_adb_24dp) {
                        HtmlText(stringResource(R.string.home_adb_description, Helps.ADB.get()))
                        HomeCardActions {
                            OutlinedButton(onClick = actions.onShowAdbCommand) { Text(stringResource(R.string.home_adb_button_view_command)) }
                        }
                    }
                }
            }
        }
    }
}
