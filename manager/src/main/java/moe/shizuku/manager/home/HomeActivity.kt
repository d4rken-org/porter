package moe.shizuku.manager.home

import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.R
import moe.shizuku.manager.Helps
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbPairingService
import moe.shizuku.manager.management.AppsViewModel
import moe.shizuku.manager.management.ApplicationManagementActivity
import moe.shizuku.manager.settings.SettingsActivity
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.starter.StarterActivity
import moe.shizuku.manager.ui.*
import moe.shizuku.manager.utils.*
import rikka.shizuku.Shizuku

abstract class HomeActivity : ComposeActivity() {
    private val homeModel: HomeViewModel by viewModels()
    private val appsModel: AppsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        porterContent { HomeScreen() }
        if (savedInstanceState == null) consumeIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent?.let { setIntent(it); consumeIntent(it) }
    }

    private fun consumeIntent(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_SHOW_PAIRING_DIALOG, false)) {
            intent.removeExtra(EXTRA_SHOW_PAIRING_DIALOG)
            showAccessibilityDialog()
        }
        if (intent.getBooleanExtra(EXTRA_START_SERVICE_VIA_WADB, false)) {
            intent.removeExtra(EXTRA_START_SERVICE_VIA_WADB)
            getSystemService(NotificationManager::class.java).cancel(AdbPairingService.NOTIFICATION_ID)
            WirelessStart.start(this, lifecycleScope)
        }
    }

    override fun onResume() {
        super.onResume()
        homeModel.reload()
        homeModel.checkBatteryOptimization()
        appsModel.load()
    }

    @Composable
    private fun HomeScreen() {
        val status by homeModel.serviceStatus.collectAsStateWithLifecycle()
        val appsState by appsModel.state.collectAsStateWithLifecycle()
        val reboot by homeModel.shouldShowRebootDialog.collectAsStateWithLifecycle()
        val duplicate by homeModel.shouldShowUninstallDialog.collectAsStateWithLifecycle()
        val battery by homeModel.shouldShowBatteryOptimizationSnackbar.collectAsStateWithLifecycle()
        val serviceState by remember { ShizukuStateMachine.asFlow() }.collectAsStateWithLifecycle(ShizukuStateMachine.get())
        LaunchedEffect(serviceState) { homeModel.reload(); if (serviceState == ShizukuStateMachine.State.RUNNING) appsModel.load() }
        val statusUi = serviceStatusUi(status, serviceState)
        val running = statusUi.running
        val restricted = statusUi.restricted
        LaunchedEffect(status) {
            if (running) ShizukuSettings.setLastLaunchMode(if (status.uid == 0) ShizukuSettings.LaunchMethod.ROOT else ShizukuSettings.LaunchMethod.ADB)
        }
        var dialog by rememberSaveable { mutableStateOf<String?>(null) }
        val buildBadge = when {
            BuildConfig.DEBUG -> stringResource(R.string.porter_build_dev)
            BuildConfig.VERSION_NAME.contains("-beta") -> stringResource(R.string.porter_build_beta)
            else -> null
        }
        PorterScaffold(stringResource(R.string.app_name), subtitle = stringResource(R.string.porter_home_subtitle),
            titleIcon = R.drawable.porter_mascot, titleBadge = buildBadge, actions = {
            IconButton(onClick = { startActivity(Intent(this@HomeActivity, SettingsActivity::class.java)) }) {
                Icon(painterResource(R.drawable.ic_action_settings_24dp), stringResource(R.string.settings_title))
            }
        }) { padding ->
            LazyColumn(Modifier.padding(padding).consumeWindowInsets(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { ServiceStatusCard(statusUi, accessPaused = appsState.accessEnabled == false) { dialog = "status" } }
                if (running && status.permission) item {
                    val count = appsState.grantedCount
                    HomeCard(stringResource(R.string.porter_applications), R.drawable.ic_apps_outline_24,
                        { startActivity(Intent(this@HomeActivity, ApplicationManagementActivity::class.java)) },
                        subtitle = if (appsState.legacy || appsState.failedUsers.isNotEmpty())
                            resources.getQuantityString(R.plurals.home_app_management_authorized_apps_count, count, count)
                        else stringResource(R.string.porter_apps_counts, count, appsState.compatibleCount)) {
                        if (appsState.companionRequiredCount > 0) Text(
                            resources.getQuantityString(R.plurals.porter_apps_need_companion, appsState.companionRequiredCount, appsState.companionRequiredCount),
                            style = MaterialTheme.typography.bodyMedium)
                        if (appsState.failedUsers.isNotEmpty()) Text(stringResource(R.string.porter_discovery_partial), style = MaterialTheme.typography.bodySmall)
                        if (appsState.accessEnabled == false) Text(stringResource(R.string.porter_access_paused),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        Text(stringResource(R.string.home_app_management_view_authorized_apps), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (running && status.permission && appsState.pendingCompanionCount > 0) item {
                    CompatibilityCard(resources.getQuantityString(R.plurals.porter_compatibility_pending,
                        appsState.pendingCompanionCount, appsState.pendingCompanionCount)) {
                        CustomTabsHelper.launchUrlOrCopy(this@HomeActivity, Helps.DOWNLOAD.get())
                    }
                }
                if (restricted) item {
                    HomeCard(stringResource(R.string.app_management_dialog_adb_is_limited_title), R.drawable.ic_warning_24) {
                        HtmlText(stringResource(R.string.app_management_dialog_adb_is_limited_message, Helps.ADB_PERMISSION.get()))
                    }
                }
                if (battery) item {
                    HomeCard(stringResource(R.string.snackbar_battery_optimization_home), R.drawable.ic_outline_info_24) {
                        TextButton(onClick = { SettingsHelper.requestIgnoreBatteryOptimizations(this@HomeActivity) }) { Text(stringResource(R.string.snackbar_action_fix)) }
                    }
                }
                if (!running && UserHandleCompat.myUserId() == 0) {
                    if (EnvironmentUtils.isRooted()) item {
                        HomeCard(stringResource(R.string.home_root_title), R.drawable.ic_root_24dp) {
                            HtmlText(stringResource(R.string.home_root_description, "<a href=\"${Helps.SUI.get()}\">Sui</a>", "Sui"))
                            HomeCardActions {
                                Button(onClick = { startActivity(Intent(this@HomeActivity, StarterActivity::class.java).putExtra(StarterActivity.EXTRA_IS_ROOT, true)) }) { Text(stringResource(R.string.home_root_button_start)) }
                            }
                        }
                    }
                    if (Build.VERSION.SDK_INT >= 30 || EnvironmentUtils.isTelevision() || EnvironmentUtils.getAdbTcpPort() > 0) item {
                        HomeCard(stringResource(R.string.home_wireless_adb_title), R.drawable.ic_wadb_24) {
                            HtmlText(stringResource(if (EnvironmentUtils.isTlsSupported()) R.string.home_wireless_adb_description else R.string.home_wireless_adb_description_pre_11))
                            if (EnvironmentUtils.isTlsSupported()) {
                                TextButton(onClick = { CustomTabsHelper.launchUrlOrCopy(this@HomeActivity, Helps.ADB_ANDROID11.get()) }) { Text(stringResource(R.string.home_wireless_adb_view_guide_button)) }
                            }
                            HomeCardActions {
                                if (EnvironmentUtils.isTlsSupported()) {
                                    OutlinedButton(onClick = { WirelessStart.pair(this@HomeActivity) }) { Text(stringResource(R.string.adb_pairing)) }
                                }
                                Button(onClick = { WirelessStart.start(this@HomeActivity, lifecycleScope) }, enabled = serviceState != ShizukuStateMachine.State.STARTING) { Text(stringResource(R.string.home_root_button_start)) }
                            }
                        }
                    }
                    item {
                        HomeCard(stringResource(R.string.home_adb_title), R.drawable.ic_adb_24dp) {
                            HtmlText(stringResource(R.string.home_adb_description, Helps.ADB.get()))
                            HomeCardActions {
                                OutlinedButton(onClick = { dialog = "command" }) { Text(stringResource(R.string.home_adb_button_view_command)) }
                            }
                        }
                    }
                }
            }
        }
        if (dialog == "status" && running) ServiceStatusDialog(statusUi, { dialog = null }) {
            dialog = null
            if (ShizukuStateMachine.isRunning()) {
                ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPING)
                runCatching { Shizuku.exit() }
            }
        }
        if (dialog == "command") AlertDialog(onDismissRequest = { dialog = null }, title = { Text(stringResource(R.string.home_adb_button_view_command)) }, text = {
            HtmlText(stringResource(R.string.home_adb_dialog_view_command_message, Starter.adbCommand))
        }, confirmButton = { TextButton(onClick = { copyText(this@HomeActivity, Starter.adbCommand); dialog = null }) { Text(stringResource(android.R.string.copy)) } },
            dismissButton = { TextButton(onClick = {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, Starter.adbCommand), getString(R.string.home_adb_dialog_view_command_button_send)))
            }) { Text(stringResource(R.string.home_adb_dialog_view_command_button_send)) } })
        if (reboot || duplicate) AlertDialog(onDismissRequest = {}, title = { Text(stringResource(if (reboot) R.string.home_dialog_reboot_required_title else R.string.home_dialog_duplicate_app_detected_title)) },
            text = { Text(stringResource(if (reboot) R.string.home_dialog_reboot_required_message else R.string.home_dialog_duplicate_app_detected_message)) },
            confirmButton = { TextButton(onClick = { finishAffinity() }) { Text(stringResource(R.string.home_dialog_button_exit)) } },
            properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false))
    }

    companion object {
        const val EXTRA_SHOW_PAIRING_DIALOG = "show_pairing_dialog"
        const val EXTRA_START_SERVICE_VIA_WADB = "start_service_via_wadb"
    }
}
