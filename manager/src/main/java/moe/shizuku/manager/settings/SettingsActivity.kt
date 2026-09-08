package moe.shizuku.manager.settings

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
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
        PorterScaffold(stringResource(R.string.settings_title), onBack = { finish() }) { padding ->
            Column(Modifier.padding(padding).consumeWindowInsets(padding).verticalScroll(rememberScrollState())) {
                SettingsCategory(stringResource(R.string.porter_startup))
                SettingsSwitch(stringResource(R.string.settings_start_on_boot), R.drawable.ic_outline_play_arrow_24,
                    ShizukuSettings.getStartOnBoot(this@SettingsActivity), enabled = canBoot,
                    summary = if (canBoot) null else stringResource(R.string.settings_start_on_boot_summary)) {
                    model.toggle(ShizukuSettings.Keys.KEY_START_ON_BOOT, it)
                }
                SettingsSwitch(stringResource(R.string.settings_watchdog), R.drawable.ic_autorenew,
                    values[ShizukuSettings.Keys.KEY_WATCHDOG] as? Boolean ?: false,
                    stringResource(R.string.settings_watchdog_summary)) { model.toggle(ShizukuSettings.Keys.KEY_WATCHDOG, it) }
                if (!EnvironmentUtils.isTelevision() && Build.VERSION.SDK_INT >= 30) {
                    SettingsItem(stringResource(R.string.porter_pairing_method), R.drawable.ic_baseline_link_24,
                        pairings[if (values[ShizukuSettings.Keys.KEY_LEGACY_PAIRING] == true) 1 else 0], onClick = { model.show("pairing") })
                }
                if (EnvironmentUtils.isTelevision() && !EnvironmentUtils.isTlsSupported()) {
                    val needsRestart = EnvironmentUtils.getAdbTcpPort().let { it > 0 && it != ShizukuSettings.getTcpPort() }
                    SettingsItem(stringResource(R.string.settings_tcp_port), if (needsRestart) R.drawable.ic_server_restart else R.drawable.ic_wadb_24,
                        (values[ShizukuSettings.Keys.KEY_TCP_PORT] as? String) ?: stringResource(R.string.settings_tcp_port_default), onClick = { model.show("tcp") })
                }
                SettingsCategory(stringResource(R.string.settings_user_interface))
                SettingsItem(stringResource(R.string.porter_theme_mode), R.drawable.ic_outline_dark_mode_24,
                    modes.getOrElse(modeValues.indexOf(mode)) { modes.first() }, onClick = { model.show("mode") })
                SettingsItem(stringResource(R.string.porter_theme_style), R.drawable.ic_contrast_24,
                    styles.getOrElse(styleValues.indexOf(style)) { styles.first() }, onClick = { model.show("style") })
                SettingsItem(stringResource(R.string.porter_theme_color), R.drawable.ic_palette_24,
                    if (style == "MATERIAL_YOU" && Build.VERSION.SDK_INT >= 31) stringResource(R.string.porter_theme_color_system)
                    else colors.getOrElse(colorValues.indexOf(color)) { colors.first() },
                    enabled = style != "MATERIAL_YOU" || Build.VERSION.SDK_INT < 31, onClick = { model.show("color") })
                SettingsCategory(stringResource(R.string.porter_tools))
                SettingsItem(stringResource(R.string.home_terminal_title), R.drawable.ic_terminal_24, stringResource(R.string.home_terminal_description),
                    onClick = { startActivity(Intent(this@SettingsActivity, moe.shizuku.manager.shell.ShellTutorialActivity::class.java)) })
                SettingsItem(stringResource(R.string.home_automation_title), R.drawable.ic_integration_instructions_24, onClick = { model.show("automation") })
                SettingsItem(stringResource(R.string.porter_developer_guide), R.drawable.ic_code_24dp, onClick = { CustomTabsHelper.launchUrlOrCopy(this@SettingsActivity, Helps.HOME.get()) })
                SettingsCategory(stringResource(R.string.settings_support))
                SettingsItem(stringResource(R.string.porter_support_title), R.drawable.ic_help_outline_24dp, stringResource(R.string.porter_support_summary),
                    onClick = { startActivity(Intent(this@SettingsActivity, moe.shizuku.manager.support.SupportActivity::class.java)) })
                SettingsItem(stringResource(R.string.porter_version), R.drawable.ic_outline_info_24, BuildConfig.VERSION_NAME,
                    onClick = { CustomTabsHelper.launchUrlOrCopy(this@SettingsActivity, Helps.DOWNLOAD.get()) })
            }
        }
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
