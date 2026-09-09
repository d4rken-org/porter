package moe.shizuku.manager.settings

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.shizuku.manager.R
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.Helps
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.receiver.NotifCancelReceiver
import moe.shizuku.manager.receiver.ShizukuReceiverStarter
import moe.shizuku.manager.ui.*
import moe.shizuku.manager.utils.*

class SettingsActivity : ComposeActivity() {
    private val model: SettingsViewModel by viewModels()
    private val battery = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { model.batteryResult() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        porterContent { SettingsScreen() }
    }

    @Composable
    private fun SettingsScreen() {
        val revision = preferencesRevision()
        val preferences = ShizukuSettings.getPreferences()
        val values = remember(revision) { preferences.all }
        val dialog by model.dialog.collectAsStateWithLifecycle()
        val mode = (values[ShizukuSettings.Keys.KEY_NIGHT_MODE] as? Int ?: -1).toString()
        val style = values[ShizukuSettings.Keys.KEY_THEME_STYLE] as? String ?: "DEFAULT"
        val color = values[ShizukuSettings.Keys.KEY_THEME_COLOR] as? String ?: "BLUE"
        val modes = stringArrayResource(R.array.night_mode).toList()
        val modeValues = resources.getIntArray(R.array.night_mode_value).map(Int::toString)
        val allStyles = stringArrayResource(R.array.porter_theme_styles).toList()
        val allStyleValues = resources.getStringArray(R.array.porter_theme_style_values).toList()
        val styleIndices = allStyleValues.indices.filter { Build.VERSION.SDK_INT >= 31 || allStyleValues[it] != "MATERIAL_YOU" }
        val styles = styleIndices.map { allStyles[it] }
        val styleValues = styleIndices.map { allStyleValues[it] }
        val colors = stringArrayResource(R.array.porter_theme_colors).toList()
        val colorValues = resources.getStringArray(R.array.porter_theme_color_values).toList()
        val pairings = stringArrayResource(R.array.porter_pairing_methods).toList()
        var tcpText by rememberSaveable { mutableStateOf(preferences.getString(ShizukuSettings.Keys.KEY_TCP_PORT, "") ?: "") }
        val canBoot = Build.VERSION.SDK_INT >= 30 || EnvironmentUtils.isTelevision() || EnvironmentUtils.isRooted()
        val showTcpPort = EnvironmentUtils.isTelevision() && !EnvironmentUtils.isTlsSupported()
        SettingsScreenContent(
            SettingsUiState(
                startOnBoot = ShizukuSettings.getStartOnBoot(this@SettingsActivity),
                startOnBootEnabled = canBoot,
                watchdog = values[ShizukuSettings.Keys.KEY_WATCHDOG] as? Boolean ?: false,
                showPairingMethod = !EnvironmentUtils.isTelevision() && Build.VERSION.SDK_INT >= 30,
                pairingMethodLabel = pairings[if (values[ShizukuSettings.Keys.KEY_LEGACY_PAIRING] == true) 1 else 0],
                showTcpPort = showTcpPort,
                tcpPortLabel = (values[ShizukuSettings.Keys.KEY_TCP_PORT] as? String) ?: stringResource(R.string.settings_tcp_port_default),
                tcpPortNeedsRestart = showTcpPort && EnvironmentUtils.getAdbTcpPort().let { it > 0 && it != ShizukuSettings.getTcpPort() },
                themeModeLabel = modes.getOrElse(modeValues.indexOf(mode)) { modes.first() },
                themeStyleLabel = styles.getOrElse(styleValues.indexOf(style)) { styles.first() },
                themeColorLabel = if (style == "MATERIAL_YOU" && Build.VERSION.SDK_INT >= 31) stringResource(R.string.porter_theme_color_system)
                    else colors.getOrElse(colorValues.indexOf(color)) { colors.first() },
                themeColorEnabled = style != "MATERIAL_YOU" || Build.VERSION.SDK_INT < 31,
                versionName = BuildConfig.VERSION_NAME,
            ),
            SettingsActions(
                onBack = { finish() },
                onStartOnBootChange = { model.toggle(ShizukuSettings.Keys.KEY_START_ON_BOOT, it) },
                onWatchdogChange = { model.toggle(ShizukuSettings.Keys.KEY_WATCHDOG, it) },
                onPairingMethod = { model.show("pairing") },
                onTcpPort = { model.show("tcp") },
                onThemeMode = { model.show("mode") },
                onThemeStyle = { model.show("style") },
                onThemeColor = { model.show("color") },
                onCompatibility = { startActivity(Intent(this@SettingsActivity, moe.shizuku.manager.compatibility.CompatibilityActivity::class.java)) },
                onTerminal = { startActivity(Intent(this@SettingsActivity, moe.shizuku.manager.shell.ShellTutorialActivity::class.java)) },
                onAutomation = { model.show("automation") },
                onDeveloperGuide = { CustomTabsHelper.launchUrlOrCopy(this@SettingsActivity, Helps.HOME.get()) },
                onSupport = { startActivity(Intent(this@SettingsActivity, moe.shizuku.manager.support.SupportActivity::class.java)) },
                onAcknowledgements = { startActivity(Intent(this@SettingsActivity, AcknowledgementsActivity::class.java)) },
                onVersion = { CustomTabsHelper.launchUrlOrCopy(this@SettingsActivity, Helps.DOWNLOAD.get()) },
            ),
        )
        val dismiss = { model.show(null) }
        when (dialog) {
            "mode" -> ChoiceDialog(stringResource(R.string.porter_theme_mode), modes, modeValues.indexOf(mode), dismiss) {
                val value = modeValues[it].toInt()
                preferences.edit().putInt(ShizukuSettings.Keys.KEY_NIGHT_MODE, value).apply()
                model.show(null)
                AppCompatDelegate.setDefaultNightMode(value)
            }
            "style", "color" -> {
                val isStyle = dialog == "style"
                ChoiceDialog(stringResource(if (isStyle) R.string.porter_theme_style else R.string.porter_theme_color),
                    if (isStyle) styles else colors, if (isStyle) styleValues.indexOf(style) else colorValues.indexOf(color), dismiss) {
                    preferences.edit().putString(if (isStyle) ShizukuSettings.Keys.KEY_THEME_STYLE else ShizukuSettings.Keys.KEY_THEME_COLOR,
                        if (isStyle) styleValues[it] else colorValues[it]).apply()
                    model.show(null)
                }
            }
            "pairing" -> ChoiceDialog(stringResource(R.string.porter_pairing_method), pairings,
                if (values[ShizukuSettings.Keys.KEY_LEGACY_PAIRING] == true) 1 else 0, dismiss) {
                preferences.edit().putBoolean(ShizukuSettings.Keys.KEY_LEGACY_PAIRING, it == 1).apply(); model.show(null)
            }
            "boot_warning" -> MessageDialog(stringResource(android.R.string.dialog_alert_title), stringResource(R.string.settings_start_on_boot_bug),
                model::cancelToggle, onConfirm = { model.checkBattery() })
            "battery" -> MessageDialog(stringResource(R.string.settings_title), stringResource(R.string.snackbar_battery_optimization_settings),
                model::cancelToggle, stringResource(R.string.snackbar_action_fix), onConfirm = {
                    model.show(null)
                    runCatching { SettingsHelper.requestIgnoreBatteryOptimizations(this@SettingsActivity, battery) }.onFailure { model.cancelToggle() }
                })
            "tcp" -> {
                val valid = tcpText.isBlank() || tcpText.toIntOrNull()?.let { it in 1..65535 } == true
                AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.settings_tcp_port)) }, text = {
                    OutlinedTextField(tcpText, { tcpText = it }, singleLine = true, isError = !valid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        supportingText = { if (!valid) Text(stringResource(R.string.snackbar_invalid_port)) })
                }, confirmButton = { TextButton(enabled = valid, onClick = {
                    val newPort = tcpText.toIntOrNull() ?: 5555
                    if (ShizukuStateMachine.isRunning() && EnvironmentUtils.getAdbTcpPort().let { it > 0 && it != newPort }) model.show("restart")
                    else { ShizukuSettings.setTcpPort(tcpText.toIntOrNull()); sendBroadcast(Intent(this@SettingsActivity, NotifCancelReceiver::class.java)); dismiss() }
                }) { Text(stringResource(android.R.string.ok)) } }, dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(android.R.string.cancel)) } })
            }
            "restart" -> MessageDialog(stringResource(R.string.settings_restart_dialog_title), plainText(stringResource(R.string.settings_restart_dialog_message)), dismiss,
                onConfirm = { ShizukuSettings.setTcpPort(tcpText.toIntOrNull()); ShizukuReceiverStarter.start(this@SettingsActivity, true); dismiss() })
            "automation" -> AutomationSheet(dismiss)
        }
    }
}
