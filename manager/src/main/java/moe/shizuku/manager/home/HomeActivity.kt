package moe.shizuku.manager.home

import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import moe.shizuku.manager.R
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.Helps
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbPairingService
import moe.shizuku.manager.management.AppsViewModel
import moe.shizuku.manager.management.ApplicationManagementActivity
import moe.shizuku.manager.model.ServiceStatus
import moe.shizuku.manager.settings.SettingsActivity
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.starter.StarterActivity
import moe.shizuku.manager.ui.*
import moe.shizuku.manager.utils.*
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants

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
        val running = serviceState == ShizukuStateMachine.State.RUNNING && status.uid != -1
        LaunchedEffect(status) {
            if (running) ShizukuSettings.setLastLaunchMode(if (status.uid == 0) ShizukuSettings.LaunchMethod.ROOT else ShizukuSettings.LaunchMethod.ADB)
        }
        val restricted = running && !status.permission
        var dialog by rememberSaveable { mutableStateOf<String?>(null) }
        val title = plainText(stringResource(if (running) R.string.home_status_service_is_running else R.string.home_status_service_not_running, stringResource(R.string.app_name)))
        val details = if (running) {
            val user = if (status.uid == 0) "root" else "adb"
            val version = "${status.apiVersion}.${status.patchVersion}"
            plainText(if (status.apiVersion != Shizuku.getLatestServiceVersion() || status.patchVersion != ShizukuApiConstants.SERVER_PATCH_VERSION) {
                stringResource(R.string.home_status_service_version_update, user, version, "${Shizuku.getLatestServiceVersion()}.${ShizukuApiConstants.SERVER_PATCH_VERSION}")
            } else stringResource(R.string.home_status_service_version, user, version)) +
                if (restricted) "\n" + stringResource(R.string.porter_status_restricted) else ""
        } else ""
        PorterScaffold(stringResource(R.string.app_name), subtitle = stringResource(R.string.porter_home_subtitle), actions = {
            IconButton(onClick = { startActivity(Intent(this@HomeActivity, SettingsActivity::class.java)) }) {
                Icon(painterResource(R.drawable.ic_action_settings_24dp), stringResource(R.string.settings_title))
            }
        }) { padding ->
            LazyColumn(Modifier.padding(padding).consumeWindowInsets(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    HomeCard(title, when { restricted -> R.drawable.ic_warning_24; running -> R.drawable.ic_server_ok_24dp; else -> R.drawable.ic_server_error_24dp },
                        if (running) ({ dialog = "status" }) else null,
                        when { restricted -> colorResource(R.color.porter_status_warning); running -> colorResource(R.color.porter_status_running); else -> MaterialTheme.colorScheme.onSurfaceVariant }) {
                        if (running) Text("$details\n${stringResource(R.string.porter_status_details_hint)}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (running && status.permission) item {
                    val count = appsState.grantedCount
                    HomeCard(resources.getQuantityString(R.plurals.home_app_management_authorized_apps_count, count, count), R.drawable.ic_apps_outline_24,
                        { startActivity(Intent(this@HomeActivity, ApplicationManagementActivity::class.java)) }) {
                        Text(stringResource(R.string.home_app_management_view_authorized_apps), style = MaterialTheme.typography.bodyMedium)
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
                            Button(onClick = { startActivity(Intent(this@HomeActivity, StarterActivity::class.java).putExtra(StarterActivity.EXTRA_IS_ROOT, true)) }) { Text(stringResource(R.string.home_root_button_start)) }
                        }
                    }
                    if (Build.VERSION.SDK_INT >= 30 || EnvironmentUtils.isTelevision() || EnvironmentUtils.getAdbTcpPort() > 0) item {
                        HomeCard(stringResource(R.string.home_wireless_adb_title), R.drawable.ic_wadb_24) {
                            HtmlText(stringResource(if (EnvironmentUtils.isTlsSupported()) R.string.home_wireless_adb_description else R.string.home_wireless_adb_description_pre_11))
                            if (EnvironmentUtils.isTlsSupported()) {
                                TextButton(onClick = { CustomTabsHelper.launchUrlOrCopy(this@HomeActivity, Helps.ADB_ANDROID11.get()) }) { Text(stringResource(R.string.home_wireless_adb_view_guide_button)) }
                                OutlinedButton(onClick = { WirelessStart.pair(this@HomeActivity) }) { Text(stringResource(R.string.adb_pairing)) }
                            }
                            Button(onClick = { WirelessStart.start(this@HomeActivity, lifecycleScope) }, enabled = serviceState != ShizukuStateMachine.State.STARTING) { Text(stringResource(R.string.home_root_button_start)) }
                        }
                    }
                    item {
                        HomeCard(stringResource(R.string.home_adb_title), R.drawable.ic_adb_24dp) {
                            HtmlText(stringResource(R.string.home_adb_description, Helps.ADB.get()))
                            OutlinedButton(onClick = { dialog = "command" }) { Text(stringResource(R.string.home_adb_button_view_command)) }
                        }
                    }
                }
            }
        }
        if (dialog == "status" && running) MessageDialog(title, "$details\n\n${stringResource(R.string.porter_status_stop_message)}", { dialog = null }, stringResource(R.string.action_stop)) {
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

@Composable
private fun HomeCard(title: String, icon: Int, onClick: (() -> Unit)? = null,
                     tint: Color = MaterialTheme.colorScheme.primary, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(painterResource(icon), null, Modifier.size(24.dp), tint)
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}
