package eu.darken.porter.manager.settings

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.darken.porter.manager.R
import eu.darken.porter.manager.PorterSettings
import eu.darken.porter.manager.receiver.NotifCancelReceiver
import eu.darken.porter.manager.receiver.PorterReceiverStarter
import eu.darken.porter.manager.ui.*
import eu.darken.porter.manager.utils.*

class StartupSettingsActivity : ComposeActivity() {
    private val model: SettingsViewModel by viewModels()
    private val battery = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { model.batteryResult() }

    /**
     * Bumped on every resume. What the two rows below report can be changed from outside this
     * screen, so the reads are keyed on this as well as on the preferences.
     */
    private var resumed by mutableIntStateOf(0)

    override fun onResume() {
        super.onResume()
        resumed++
        if (model.awaitingAlerts.value) model.alertsResult()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        porterContent { StartupSettingsScreen() }
    }

    @Composable
    private fun StartupSettingsScreen() {
        val revision = preferencesRevision()
        val preferences = PorterSettings.preferences
        val values = remember(revision) { preferences.all }
        val dialog by model.dialog.collectAsStateWithLifecycle()
        val canBoot by model.canBoot.collectAsStateWithLifecycle()
        val togglesBusy by model.busy.collectAsStateWithLifecycle()
        val batteryRowNeeded = remember(revision, resumed, togglesBusy) { StartupAttention.batteryRowNeeded(this@StartupSettingsActivity) }
        val alertsBlocked = remember(revision, resumed, togglesBusy) { StartupAttention.alertsBlocked(this@StartupSettingsActivity) }
        val pairings = stringArrayResource(R.array.porter_pairing_methods).toList()
        var tcpText by rememberSaveable { mutableStateOf(preferences.getString(PorterSettings.Keys.KEY_TCP_PORT, "") ?: "") }
        val showTcpPort = EnvironmentUtils.isTelevision() && !EnvironmentUtils.isTlsSupported()
        StartupSettingsContent(
            StartupSettingsUiState(
                startOnBoot = PorterSettings.isStartOnBoot(this@StartupSettingsActivity),
                startOnBootEnabled = canBoot == true,
                togglesBusy = togglesBusy,
                watchdog = values[PorterSettings.Keys.KEY_WATCHDOG] as? Boolean ?: false,
                autoUpdateService = values[PorterSettings.Keys.KEY_AUTO_UPDATE_SERVICE] as? Boolean ?: false,
                autoUpdateServiceEnabled = UserHandleCompat.myUserId() == 0,
                showPairingMethod = !EnvironmentUtils.isTelevision() && Build.VERSION.SDK_INT >= 30,
                pairingMethodLabel = pairings[if (values[PorterSettings.Keys.KEY_LEGACY_PAIRING] == true) 1 else 0],
                showTcpPort = showTcpPort,
                tcpPortLabel = (values[PorterSettings.Keys.KEY_TCP_PORT] as? String) ?: stringResource(R.string.settings_tcp_port_default),
                tcpPortNeedsRestart = showTcpPort && EnvironmentUtils.getAdbTcpPort().let { it > 0 && it != PorterSettings.tcpPort },
                showBatteryAction = batteryRowNeeded,
                showAlertsAction = alertsBlocked,
            ),
            StartupSettingsActions(
                onBack = { finish() },
                onStartOnBootChange = { model.toggle(PorterSettings.Keys.KEY_START_ON_BOOT, it) },
                onWatchdogChange = { model.toggle(PorterSettings.Keys.KEY_WATCHDOG, it) },
                onAutoUpdateServiceChange = { model.setAutoUpdateService(it) },
                onPairingMethod = { model.show("pairing") },
                onTcpPort = { model.show("tcp") },
                onBatteryOptimization = { SettingsHelper.requestIgnoreBatteryOptimizations(this@StartupSettingsActivity) },
                onNotificationSettings = { SettingsPage.Notifications.NotificationSettings.launch(this@StartupSettingsActivity) },
            ),
        )
        val dismiss = { model.show(null) }
        when (dialog) {
            "pairing" -> ChoiceDialog(stringResource(R.string.porter_pairing_method), pairings,
                if (values[PorterSettings.Keys.KEY_LEGACY_PAIRING] == true) 1 else 0, dismiss) {
                preferences.edit().putBoolean(PorterSettings.Keys.KEY_LEGACY_PAIRING, it == 1).apply(); model.show(null)
            }
            "boot_warning" -> MessageDialog(stringResource(android.R.string.dialog_alert_title), stringResource(R.string.settings_start_on_boot_bug),
                model::cancelToggle, onConfirm = { model.checkBattery() })
            "battery" -> MessageDialog(stringResource(R.string.settings_title), stringResource(R.string.snackbar_battery_optimization_settings),
                model::cancelToggle, stringResource(R.string.snackbar_action_fix), onConfirm = {
                    model.show(null)
                    runCatching { SettingsHelper.requestIgnoreBatteryOptimizations(this@StartupSettingsActivity, battery) }.onFailure { model.cancelToggle() }
                })
            "alerts" -> AlertDialog(
                onDismissRequest = model::cancelToggle,
                title = { Text(stringResource(R.string.settings_alerts_blocked_title)) },
                text = { Text(stringResource(R.string.settings_alerts_blocked_message)) },
                confirmButton = {
                    TextButton(onClick = {
                        model.show(null)
                        model.awaitAlertsChoice()
                        SettingsPage.Notifications.NotificationSettings.launch(this@StartupSettingsActivity)
                    }) { Text(stringResource(R.string.notification_settings)) }
                },
                dismissButton = {
                    TextButton(onClick = model::applyWithoutAlerts) { Text(stringResource(R.string.settings_alerts_blocked_continue)) }
                },
            )
            "tcp" -> {
                val valid = tcpText.isBlank() || tcpText.toIntOrNull()?.let { it in 1..65535 } == true
                AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.settings_tcp_port)) }, text = {
                    OutlinedTextField(tcpText, { tcpText = it }, singleLine = true, isError = !valid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        supportingText = { if (!valid) Text(stringResource(R.string.snackbar_invalid_port)) })
                }, confirmButton = { TextButton(enabled = valid, onClick = {
                    val newPort = tcpText.toIntOrNull() ?: 5555
                    if (PorterStateMachine.instance.isRunning() && EnvironmentUtils.getAdbTcpPort().let { it > 0 && it != newPort }) model.show("restart")
                    else { PorterSettings.setTcpPort(tcpText.toIntOrNull()); sendBroadcast(Intent(this@StartupSettingsActivity, NotifCancelReceiver::class.java)); dismiss() }
                }) { Text(stringResource(android.R.string.ok)) } }, dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(android.R.string.cancel)) } })
            }
            "restart" -> MessageDialog(stringResource(R.string.settings_restart_dialog_title), plainText(stringResource(R.string.settings_restart_dialog_message)), dismiss,
                onConfirm = { PorterSettings.setTcpPort(tcpText.toIntOrNull()); PorterReceiverStarter.start(this@StartupSettingsActivity, true); dismiss() })
        }
    }
}
