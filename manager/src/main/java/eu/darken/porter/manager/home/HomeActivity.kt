package eu.darken.porter.manager.home

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import eu.darken.porter.manager.BuildConfig
import eu.darken.porter.manager.Helps
import eu.darken.porter.manager.MainActivity
import eu.darken.porter.manager.starter.ServiceReplacement
import eu.darken.porter.manager.starter.Starter
import eu.darken.porter.manager.starter.StarterActivity
import eu.darken.porter.manager.compatibility.CompatibilityRepository
import eu.darken.porter.manager.R
import eu.darken.porter.manager.PorterSettings
import eu.darken.porter.manager.adb.TvPairingResultStore
import eu.darken.porter.manager.adb.AdbPairingService
import eu.darken.porter.manager.management.AppsViewModel
import eu.darken.porter.manager.management.ApplicationManagementActivity
import eu.darken.porter.manager.settings.SettingsActivity
import eu.darken.porter.manager.ui.*
import eu.darken.porter.manager.updater.Asset
import eu.darken.porter.manager.updater.UpdateInstaller
import eu.darken.porter.manager.updater.UpdateRepository
import eu.darken.porter.manager.updater.canInstallUpdate
import eu.darken.porter.manager.utils.*

abstract class HomeActivity : ComposeActivity() {
    override val protectTouches = true
    private val homeModel: HomeViewModel by viewModels()
    private val service by lazy { eu.darken.porter.manager.service.ServiceStatusRepository.get(this) }
    private val appsModel: AppsViewModel by viewModels()
    private val compatibility by lazy { CompatibilityRepository.get(this) }
    private val updates by lazy { UpdateRepository.get(this) }
    private val updateInstaller by lazy { UpdateInstaller.get(this) }

    /** The release chosen in the update dialog, kept across the external screen the choice opens. */
    private var pendingUpdate: PendingUpdate? = null
    private val updateSaveTarget = registerForActivityResult(ActivityResultContracts.CreateDocument(APK_MIME_TYPE)) { uri ->
        val pending = takePendingUpdate(UpdateAction.DOWNLOAD) ?: return@registerForActivityResult
        if (uri != null) updateInstaller.save(pending.asset, uri)
    }
    private val updateInstallPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val pending = takePendingUpdate(UpdateAction.INSTALL_MANUAL) ?: return@registerForActivityResult
        if (Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()) updateInstaller.installManually(pending.asset)
        else updateInstaller.reportFailure(getString(R.string.updater_install_permission_denied))
    }
    private val updateSystemInstaller = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        updateInstaller.finishManualInstall()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingUpdate = PendingUpdate.from(savedInstanceState?.getBundle(STATE_PENDING_UPDATE))
        porterContent { HomeScreen() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) { compatibility.refresh(); delay(3000) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                updateInstaller.handoff.collect { if (it != null) openSystemInstaller() }
            }
        }
        if (savedInstanceState == null) consumeIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingUpdate?.let { outState.putBundle(STATE_PENDING_UPDATE, it.toBundle()) }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) updateInstaller.finishManualInstall()
    }

    /** Clears the choice whatever it was, so a result never reuses it. */
    private fun takePendingUpdate(action: UpdateAction): PendingUpdate? =
        pendingUpdate.also { pendingUpdate = null }?.takeIf { it.action == action }

    private fun startUpdate(action: UpdateAction, tag: String, asset: Asset) {
        pendingUpdate = PendingUpdate(action, tag, asset)
        when (action) {
            UpdateAction.DOWNLOAD -> try {
                updateSaveTarget.launch(asset.fileName)
            } catch (e: ActivityNotFoundException) {
                pendingUpdate = null
                updateInstaller.reportFailure(getString(R.string.updater_save_unavailable))
            }
            UpdateAction.INSTALL_MANUAL -> {
                if (!BuildConfig.IS_FOSS) {
                    pendingUpdate = null
                    return
                }
                if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
                    try {
                        updateInstallPermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                    } catch (e: ActivityNotFoundException) {
                        pendingUpdate = null
                        updateInstaller.reportFailure(getString(R.string.updater_install_permission_unavailable))
                    }
                    return
                }
                pendingUpdate = null
                updateInstaller.installManually(asset)
            }
            UpdateAction.INSTALL_AUTOMATIC -> {
                pendingUpdate = null
                updateInstaller.installAutomatically(asset)
            }
        }
    }

    private fun openSystemInstaller() {
        val apk = updateInstaller.takeHandoff() ?: return
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
            updateSystemInstaller.launch(Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_MIME_TYPE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            LOGGER.w(e, "Open Android's installer for the Porter update")
            updateInstaller.finishManualInstall(getString(R.string.updater_installer_unavailable))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent?.let { setIntent(it); consumeIntent(it) }
    }

    private fun consumeIntent(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_START_PAIRING, false)) {
            intent.removeExtra(EXTRA_START_PAIRING)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && EnvironmentUtils.isTlsSupported()) WirelessStart.pair(this)
        }
        if (!intent.hasExtra(EXTRA_START_SERVICE_VIA_WADB)) return
        val start = intent.getBooleanExtra(EXTRA_START_SERVICE_VIA_WADB, false)
        intent.removeExtra(EXTRA_START_SERVICE_VIA_WADB)
        if (intent.component?.className != WIRELESS_START_ALIAS) {
            LOGGER.w("Ignoring a wireless debugging start that did not come through %s", WIRELESS_START_ALIAS)
            return
        }
        if (start) {
            getSystemService(NotificationManager::class.java).cancel(AdbPairingService.RESULT_NOTIFICATION_ID)
            val blocker = when {
                UserHandleCompat.myUserId() != 0 -> "notPrimaryUser"
                ServiceReplacement.get(this).state.value.running -> "replacementRunning"
                PorterStateMachine.instance.isRunning() -> "porterRunning"
                else -> null
            }
            if (blocker == null) WirelessStart.start(this, lifecycleScope)
            else LOGGER.i("Wireless start alias ignored: %s", blocker)
        }
    }

    private fun canStart(action: String): Boolean {
        val snapshot = service.state.value
        if (!snapshot.canStart) LOGGER.i("%s tap ignored: serviceState=%s busy=%b primaryUser=%b",
            action, snapshot.serviceState, snapshot.busy, snapshot.primaryUser)
        return snapshot.canStart
    }

    override fun onResume() {
        super.onResume()
        homeModel.reload()
        service.refresh()
        homeModel.checkBatteryOptimization()
        if (PorterStateMachine.instance.isRunning()) appsModel.load()
        compatibility.refresh()
        updates.refresh()
    }

    override fun onPostResume() {
        super.onPostResume()
        val result = TvPairingResultStore(PorterSettings.preferences).read()
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
        val snapshot by service.state.collectAsStateWithLifecycle()
        var dialog by rememberSaveable { mutableStateOf<String?>(null) }
        val wirelessAdbAvailable = remember { Build.VERSION.SDK_INT >= 30 || EnvironmentUtils.isTelevision() || EnvironmentUtils.getAdbTcpPort() > 0 }
        val tlsSupported = remember { EnvironmentUtils.isTlsSupported() }
        val appsState by appsModel.state.collectAsStateWithLifecycle()
        val compatState by compatibility.state.collectAsStateWithLifecycle()
        val reboot by homeModel.shouldShowRebootDialog.collectAsStateWithLifecycle()
        val duplicate by homeModel.shouldShowUninstallDialog.collectAsStateWithLifecycle()
        val battery by homeModel.shouldShowBatteryOptimizationSnackbar.collectAsStateWithLifecycle()
        val updateState by updates.state.collectAsStateWithLifecycle()
        val update = updateState.update
        val updateOperation by updateInstaller.operation.collectAsStateWithLifecycle()
        var updateDialog by rememberSaveable { mutableStateOf(false) }
        val updateApk = update?.release?.apk
        LaunchedEffect(updateApk) { if (updateApk == null) updateDialog = false }
        val serviceState = snapshot.serviceState
        LaunchedEffect(serviceState) { if (serviceState == PorterStateMachine.State.RUNNING) appsModel.load() }
        val statusUi = serviceStatusUi(snapshot)
        val running = snapshot.running
        LaunchedEffect(compatState.status, compatState.installedVersionCode) {
            if (running) appsModel.load()
        }
        val buildBadge = when {
            BuildConfig.DEBUG -> stringResource(R.string.porter_build_dev)
            BuildConfig.VERSION_NAME.contains("-beta") -> stringResource(R.string.porter_build_beta)
            else -> null
        }
        HomeScreenContent(
            HomeUiState(statusUi, appsState, compatState, buildBadge, battery,
                canStart = snapshot.canStart, primaryUser = snapshot.primaryUser, integratedCompatibility = BuildConfig.IS_FOSS,
                busy = snapshot.busy,
                wirelessAdbAvailable = wirelessAdbAvailable, tlsSupported = tlsSupported,
                update = update?.toCardState(updateOperation)),
            HomeActions(
                onOpenSettings = { startActivity(Intent(this@HomeActivity, SettingsActivity::class.java)) },
                onOpenApps = { startActivity(Intent(this@HomeActivity, ApplicationManagementActivity::class.java)) },
                onOpenCompatibility = { startActivity(Intent(this@HomeActivity, eu.darken.porter.manager.compatibility.CompatibilityActivity::class.java)) },
                onOpenService = { startActivity(Intent(this@HomeActivity, eu.darken.porter.manager.service.ServiceActivity::class.java)) },
                onStartRoot = { if (canStart("Root start")) startActivity(Intent(this@HomeActivity, StarterActivity::class.java).putExtra(StarterActivity.EXTRA_IS_ROOT, true)) },
                onPairWireless = { if (canStart("Wireless pair") && EnvironmentUtils.isTlsSupported()) WirelessStart.pair(this@HomeActivity) },
                onStartWireless = { if (canStart("Wireless start")) WirelessStart.start(this@HomeActivity, lifecycleScope) },
                onViewWirelessGuide = { CustomTabsHelper.launchUrlOrCopy(this@HomeActivity, Helps.ADB_ANDROID11.get()) },
                onShowAdbCommand = { if (canStart("ADB command")) dialog = "command" },
                onUpdateIgnore = { updates.dismiss() },
                onUpdateChangelog = { update?.let { CustomTabsHelper.launchUrlOrCopy(this@HomeActivity, it.release.changelogUrl) } },
                onUpdateDownload = { updateDialog = true },
            ),
        )
        if (updateDialog && update != null && updateApk != null) UpdateDownloadDialog(updateApk.fileName, snapshot.canInstallUpdate,
            onConfirm = { action -> updateDialog = false; startUpdate(action, update.release.tag, updateApk) },
            onDismiss = { updateDialog = false })
        if (dialog == "command") AlertDialog(onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.home_adb_button_view_command)) },
            text = { HtmlText(stringResource(R.string.home_adb_dialog_view_command_message, Starter.adbCommand)) },
            confirmButton = { TextButton(onClick = { copyText(this@HomeActivity, Starter.adbCommand); dialog = null }) { Text(stringResource(android.R.string.copy)) } },
            dismissButton = { TextButton(onClick = {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, Starter.adbCommand), getString(R.string.home_adb_dialog_view_command_button_send)))
            }) { Text(stringResource(R.string.home_adb_dialog_view_command_button_send)) } })
        if (reboot || duplicate) AlertDialog(onDismissRequest = {}, title = { Text(stringResource(if (reboot) R.string.home_dialog_reboot_required_title else R.string.home_dialog_duplicate_app_detected_title)) },
            text = { Text(stringResource(if (reboot) R.string.home_dialog_reboot_required_message else R.string.home_dialog_duplicate_app_detected_message)) },
            confirmButton = { TextButton(onClick = { finishAffinity() }) { Text(stringResource(R.string.home_dialog_button_exit)) } },
            properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false))
    }

    private data class PendingUpdate(val action: UpdateAction, val tag: String, val asset: Asset) {
        fun toBundle() = Bundle().apply {
            putString(KEY_ACTION, action.name)
            putString(KEY_TAG, tag)
            putString(KEY_FILE_NAME, asset.fileName)
            putString(KEY_URL, asset.url)
            putLong(KEY_SIZE, asset.size)
        }

        companion object {
            private const val KEY_ACTION = "action"
            private const val KEY_TAG = "tag"
            private const val KEY_FILE_NAME = "file_name"
            private const val KEY_URL = "url"
            private const val KEY_SIZE = "size"

            fun from(bundle: Bundle?): PendingUpdate? {
                bundle ?: return null
                val action = UpdateAction.entries.firstOrNull { it.name == bundle.getString(KEY_ACTION) } ?: return null
                return PendingUpdate(action, bundle.getString(KEY_TAG) ?: return null, Asset(
                    fileName = bundle.getString(KEY_FILE_NAME) ?: return null,
                    url = bundle.getString(KEY_URL) ?: return null,
                    size = bundle.getLong(KEY_SIZE),
                ))
            }
        }
    }

    companion object {
        private const val STATE_PENDING_UPDATE = "pending_update"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        const val EXTRA_SHOW_PAIRING_DIALOG = "show_pairing_dialog"
        const val EXTRA_START_SERVICE_VIA_WADB = "start_service_via_wadb"
        private const val EXTRA_START_PAIRING = "start_pairing"

        /** The action gives PendingIntents built from this an identity of their own; extras don't count toward it. */
        fun pairingIntent(context: Context): Intent = Intent(context, MainActivity::class.java)
            .setAction("${BuildConfig.APPLICATION_ID}.action.START_PAIRING")
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_START_PAIRING, true)

        /** The non-exported alias of the launcher activity, the only way [EXTRA_START_SERVICE_VIA_WADB] is honoured. */
        const val WIRELESS_START_ALIAS = "eu.darken.porter.manager.home.WirelessStartAlias"
    }
}
