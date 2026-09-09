package moe.shizuku.manager.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
)

internal data class SettingsActions(
    val onBack: () -> Unit,
    val onStartOnBootChange: (Boolean) -> Unit,
    val onWatchdogChange: (Boolean) -> Unit,
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
)

@DrawableRes
internal fun tcpPortIcon(needsRestart: Boolean) =
    if (needsRestart) R.drawable.ic_server_restart else R.drawable.ic_wadb_24

@Composable
internal fun SettingsScreenContent(state: SettingsUiState, actions: SettingsActions, modifier: Modifier = Modifier) {
    PorterScaffold(stringResource(R.string.settings_title), onBack = actions.onBack) { padding ->
        Column(modifier.padding(padding).consumeWindowInsets(padding).verticalScroll(rememberScrollState())) {
            SettingsCategory(stringResource(R.string.porter_startup))
            SettingsSwitch(stringResource(R.string.settings_start_on_boot), R.drawable.ic_outline_play_arrow_24,
                state.startOnBoot, enabled = state.startOnBootEnabled,
                summary = if (state.startOnBootEnabled) null else stringResource(R.string.settings_start_on_boot_summary),
                onCheckedChange = actions.onStartOnBootChange)
            SettingsSwitch(stringResource(R.string.settings_watchdog), R.drawable.ic_autorenew,
                state.watchdog, stringResource(R.string.settings_watchdog_summary),
                onCheckedChange = actions.onWatchdogChange)
            if (state.showPairingMethod) {
                SettingsItem(stringResource(R.string.porter_pairing_method), R.drawable.ic_baseline_link_24,
                    state.pairingMethodLabel, onClick = actions.onPairingMethod)
            }
            if (state.showTcpPort) {
                SettingsItem(stringResource(R.string.settings_tcp_port), tcpPortIcon(state.tcpPortNeedsRestart),
                    state.tcpPortLabel, onClick = actions.onTcpPort)
            }
            SettingsCategory(stringResource(R.string.settings_user_interface))
            SettingsItem(stringResource(R.string.porter_theme_mode), R.drawable.ic_outline_dark_mode_24,
                state.themeModeLabel, onClick = actions.onThemeMode)
            SettingsItem(stringResource(R.string.porter_theme_style), R.drawable.ic_contrast_24,
                state.themeStyleLabel, onClick = actions.onThemeStyle)
            SettingsItem(stringResource(R.string.porter_theme_color), R.drawable.ic_palette_24,
                state.themeColorLabel, enabled = state.themeColorEnabled, onClick = actions.onThemeColor)
            SettingsCategory(stringResource(R.string.porter_tools))
            SettingsItem(stringResource(R.string.compat_setup_title), R.drawable.ic_apps_outline_24,
                onClick = actions.onCompatibility)
            SettingsItem(stringResource(R.string.home_terminal_title), R.drawable.ic_terminal_24, stringResource(R.string.home_terminal_description),
                onClick = actions.onTerminal)
            SettingsItem(stringResource(R.string.home_automation_title), R.drawable.ic_integration_instructions_24, onClick = actions.onAutomation)
            SettingsItem(stringResource(R.string.porter_developer_guide), R.drawable.ic_code_24dp, onClick = actions.onDeveloperGuide)
            SettingsCategory(stringResource(R.string.settings_support))
            SettingsItem(stringResource(R.string.porter_support_title), R.drawable.ic_help_outline_24dp, stringResource(R.string.porter_support_summary),
                onClick = actions.onSupport)
            SettingsItem(stringResource(R.string.porter_acknowledgements), R.drawable.ic_favorite_outline_24,
                stringResource(R.string.porter_acknowledgements_summary),
                onClick = actions.onAcknowledgements)
            SettingsItem(stringResource(R.string.porter_version), R.drawable.ic_outline_info_24, state.versionName,
                onClick = actions.onVersion)
        }
    }
}
