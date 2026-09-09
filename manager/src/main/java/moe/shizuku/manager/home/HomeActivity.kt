package moe.shizuku.manager.home

import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.compatibility.CompatibilityRepository
import moe.shizuku.manager.R
import moe.shizuku.manager.Helps
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.TvPairingResultStore
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
    private val compatibility by lazy { CompatibilityRepository.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        porterContent { HomeScreen() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) { compatibility.refresh(); delay(3000) }
            }
        }
        if (savedInstanceState == null) consumeIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent?.let { setIntent(it); consumeIntent(it) }
    }

    private fun consumeIntent(intent: Intent) {
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
        compatibility.refresh()
    }

    override fun onPostResume() {
        super.onPostResume()
        val result = TvPairingResultStore(ShizukuSettings.getPreferences()).read()
        if (result != null) {
            (supportFragmentManager.findFragmentByTag(AccessibilityDialogFragment::class.java.simpleName)
                as? AccessibilityDialogFragment)?.dismiss()
            intent.removeExtra(EXTRA_SHOW_PAIRING_DIALOG)
            TvPairingResultDialogFragment.create(result).show(supportFragmentManager)
        } else if (intent.getBooleanExtra(EXTRA_SHOW_PAIRING_DIALOG, false)) {
            intent.removeExtra(EXTRA_SHOW_PAIRING_DIALOG)
            showAccessibilityDialog()
        }
    }

    @Composable
    private fun HomeScreen() {
        val status by homeModel.serviceStatus.collectAsStateWithLifecycle()
        val appsState by appsModel.state.collectAsStateWithLifecycle()
        val compatState by compatibility.state.collectAsStateWithLifecycle()
        val reboot by homeModel.shouldShowRebootDialog.collectAsStateWithLifecycle()
        val duplicate by homeModel.shouldShowUninstallDialog.collectAsStateWithLifecycle()
        val battery by homeModel.shouldShowBatteryOptimizationSnackbar.collectAsStateWithLifecycle()
        val serviceState by remember { ShizukuStateMachine.asFlow() }.collectAsStateWithLifecycle(ShizukuStateMachine.get())
        LaunchedEffect(serviceState) { homeModel.reload(); if (serviceState == ShizukuStateMachine.State.RUNNING) appsModel.load() }
        val statusUi = serviceStatusUi(status, serviceState)
        val running = statusUi.running
        LaunchedEffect(compatState.status, compatState.installedVersionCode) {
            if (running) appsModel.load()
        }
        LaunchedEffect(status) {
            if (running) ShizukuSettings.setLastLaunchMode(if (status.uid == 0) ShizukuSettings.LaunchMethod.ROOT else ShizukuSettings.LaunchMethod.ADB)
        }
        var dialog by rememberSaveable { mutableStateOf<String?>(null) }
        val buildBadge = when {
            BuildConfig.DEBUG -> stringResource(R.string.porter_build_dev)
            BuildConfig.VERSION_NAME.contains("-beta") -> stringResource(R.string.porter_build_beta)
            else -> null
        }
        // isRooted() blocks on Shell.getShell(), which can ask for root, and the wireless probe
        // reads system properties: both stay behind the condition that used to guard their cards.
        val isSecondaryUser = UserHandleCompat.myUserId() != 0
        val setup = !running && !isSecondaryUser
        HomeScreenContent(
            HomeUiState(
                statusUi = statusUi,
                permitted = status.permission,
                appsState = appsState,
                compatState = compatState,
                buildBadge = buildBadge,
                showBatteryCard = battery,
                isSecondaryUser = isSecondaryUser,
                isRooted = setup && EnvironmentUtils.isRooted(),
                wirelessAdbAvailable = setup && (Build.VERSION.SDK_INT >= 30 || EnvironmentUtils.isTelevision() || EnvironmentUtils.getAdbTcpPort() > 0),
                tlsSupported = EnvironmentUtils.isTlsSupported(),
                serviceState = serviceState,
            ),
            HomeActions(
                onOpenSettings = { startActivity(Intent(this@HomeActivity, SettingsActivity::class.java)) },
                onOpenApps = { startActivity(Intent(this@HomeActivity, ApplicationManagementActivity::class.java)) },
                onOpenCompatibility = { startActivity(Intent(this@HomeActivity, moe.shizuku.manager.compatibility.CompatibilityActivity::class.java)) },
                onShowStatusDetails = { dialog = "status" },
                onFixBatteryOptimization = { SettingsHelper.requestIgnoreBatteryOptimizations(this@HomeActivity) },
                onStartRoot = { startActivity(Intent(this@HomeActivity, StarterActivity::class.java).putExtra(StarterActivity.EXTRA_IS_ROOT, true)) },
                onPairWireless = { WirelessStart.pair(this@HomeActivity) },
                onStartWireless = { WirelessStart.start(this@HomeActivity, lifecycleScope) },
                onViewWirelessGuide = { CustomTabsHelper.launchUrlOrCopy(this@HomeActivity, Helps.ADB_ANDROID11.get()) },
                onShowAdbCommand = { dialog = "command" },
            ),
        )
        if (dialog == "status" && running) ServiceStatusDialog(statusUi, { dialog = null }, secondaryUser = UserHandleCompat.myUserId() != 0) {
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
