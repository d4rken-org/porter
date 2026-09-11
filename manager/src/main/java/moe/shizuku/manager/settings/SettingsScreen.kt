package moe.shizuku.manager.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.HelpOutline
import androidx.compose.material.icons.twotone.Apps
import androidx.compose.material.icons.twotone.Autorenew
import androidx.compose.material.icons.twotone.Code
import androidx.compose.material.icons.twotone.Contrast
import androidx.compose.material.icons.twotone.DarkMode
import androidx.compose.material.icons.twotone.Favorite
import androidx.compose.material.icons.twotone.Info
import androidx.compose.material.icons.twotone.IntegrationInstructions
import androidx.compose.material.icons.twotone.Link
import androidx.compose.material.icons.twotone.Palette
import androidx.compose.material.icons.twotone.PlayArrow
import androidx.compose.material.icons.twotone.RestartAlt
import androidx.compose.material.icons.twotone.SystemUpdate
import androidx.compose.material.icons.twotone.Terminal
import androidx.compose.material.icons.twotone.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import moe.shizuku.manager.R
import moe.shizuku.manager.ui.PorterScaffold
import moe.shizuku.manager.ui.SettingsCategory
import moe.shizuku.manager.ui.SettingsItem
import moe.shizuku.manager.ui.SettingsSwitch

/** Toggle states and already-resolved row labels; the activity owns the preference reads. */
internal data class SettingsUiState(
    val startOnBoot: Boolean,
    val startOnBootEnabled: Boolean,
    val watchdog: Boolean,
    val autoUpdateService: Boolean,
    val autoUpdateServiceEnabled: Boolean,
    val showPairingMethod: Boolean,
    val pairingMethodLabel: String,
    val showTcpPort: Boolean,
    val tcpPortLabel: String,
    val tcpPortNeedsRestart: Boolean,
    val themeModeLabel: String,
    val themeStyleLabel: String,
    val themeColorLabel: String,
    val themeColorEnabled: Boolean,
    val versionName: String,
    val showBatteryAction: Boolean = false,
)

internal data class SettingsActions(
    val onBack: () -> Unit,
    val onStartOnBootChange: (Boolean) -> Unit,
    val onWatchdogChange: (Boolean) -> Unit,
    val onAutoUpdateServiceChange: (Boolean) -> Unit,
    val onPairingMethod: () -> Unit,
    val onTcpPort: () -> Unit,
    val onThemeMode: () -> Unit,
    val onThemeStyle: () -> Unit,
    val onThemeColor: () -> Unit,
    val onCompatibility: () -> Unit,
    val onTerminal: () -> Unit,
    val onAutomation: () -> Unit,
    val onDeveloperGuide: () -> Unit,
    val onSupport: () -> Unit,
    val onAcknowledgements: () -> Unit,
    val onVersion: () -> Unit,
    val onBatteryOptimization: () -> Unit = {},
)

internal fun tcpPortIcon(needsRestart: Boolean): ImageVector =
    if (needsRestart) Icons.TwoTone.RestartAlt else Icons.TwoTone.Wifi

@Composable
internal fun SettingsScreenContent(state: SettingsUiState, actions: SettingsActions, modifier: Modifier = Modifier) {
    PorterScaffold(stringResource(R.string.settings_title), onBack = actions.onBack) { padding ->
        Column(modifier.padding(padding).consumeWindowInsets(padding).verticalScroll(rememberScrollState())) {
            SettingsCategory(stringResource(R.string.porter_startup))
            SettingsSwitch(stringResource(R.string.settings_start_on_boot), Icons.TwoTone.PlayArrow,
                state.startOnBoot, enabled = state.startOnBootEnabled,
                summary = if (state.startOnBootEnabled) null else stringResource(R.string.settings_start_on_boot_summary),
                onCheckedChange = actions.onStartOnBootChange)
            SettingsSwitch(stringResource(R.string.settings_watchdog), Icons.TwoTone.Autorenew,
                state.watchdog, stringResource(R.string.settings_watchdog_summary),
                onCheckedChange = actions.onWatchdogChange)
            SettingsSwitch(stringResource(R.string.porter_service_auto_update), Icons.TwoTone.SystemUpdate,
                state.autoUpdateService, enabled = state.autoUpdateServiceEnabled,
                summary = stringResource(if (state.autoUpdateServiceEnabled) R.string.porter_service_auto_update_summary else R.string.porter_service_update_primary),
                onCheckedChange = actions.onAutoUpdateServiceChange)
            if (state.showBatteryAction) SettingsItem(stringResource(R.string.porter_background_operation), Icons.TwoTone.Info,
                stringResource(R.string.snackbar_battery_optimization_home), onClick = actions.onBatteryOptimization)
            if (state.showPairingMethod) {
                SettingsItem(stringResource(R.string.porter_pairing_method), Icons.TwoTone.Link,
                    state.pairingMethodLabel, onClick = actions.onPairingMethod)
            }
            if (state.showTcpPort) {
                SettingsItem(stringResource(R.string.settings_tcp_port), tcpPortIcon(state.tcpPortNeedsRestart),
                    state.tcpPortLabel, onClick = actions.onTcpPort)
            }
            SettingsCategory(stringResource(R.string.settings_user_interface))
            SettingsItem(stringResource(R.string.porter_theme_mode), Icons.TwoTone.DarkMode,
                state.themeModeLabel, onClick = actions.onThemeMode)
            SettingsItem(stringResource(R.string.porter_theme_style), Icons.TwoTone.Contrast,
                state.themeStyleLabel, onClick = actions.onThemeStyle)
            SettingsItem(stringResource(R.string.porter_theme_color), Icons.TwoTone.Palette,
                state.themeColorLabel, enabled = state.themeColorEnabled, onClick = actions.onThemeColor)
            SettingsCategory(stringResource(R.string.porter_tools))
            SettingsItem(stringResource(R.string.compat_setup_title), Icons.TwoTone.Apps,
                onClick = actions.onCompatibility)
            SettingsItem(stringResource(R.string.home_terminal_title), Icons.TwoTone.Terminal, stringResource(R.string.home_terminal_description),
                onClick = actions.onTerminal)
            SettingsItem(stringResource(R.string.home_automation_title), Icons.TwoTone.IntegrationInstructions, onClick = actions.onAutomation)
            SettingsItem(stringResource(R.string.porter_developer_guide), Icons.TwoTone.Code, onClick = actions.onDeveloperGuide)
            SettingsCategory(stringResource(R.string.settings_support))
            SettingsItem(stringResource(R.string.porter_support_title), Icons.AutoMirrored.TwoTone.HelpOutline, stringResource(R.string.porter_support_summary),
                onClick = actions.onSupport)
            SettingsItem(stringResource(R.string.porter_acknowledgements), Icons.TwoTone.Favorite,
                stringResource(R.string.porter_acknowledgements_summary),
                onClick = actions.onAcknowledgements)
            SettingsItem(stringResource(R.string.porter_version), Icons.TwoTone.Info, state.versionName,
                onClick = actions.onVersion)
        }
    }
}
