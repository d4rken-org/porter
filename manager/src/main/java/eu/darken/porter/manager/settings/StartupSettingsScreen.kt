package eu.darken.porter.manager.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Autorenew
import androidx.compose.material.icons.twotone.Info
import androidx.compose.material.icons.twotone.Link
import androidx.compose.material.icons.twotone.NotificationsOff
import androidx.compose.material.icons.twotone.PlayArrow
import androidx.compose.material.icons.twotone.RestartAlt
import androidx.compose.material.icons.twotone.SystemUpdate
import androidx.compose.material.icons.twotone.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import eu.darken.porter.manager.R
import eu.darken.porter.manager.ui.PorterScaffold
import eu.darken.porter.manager.ui.SettingsItem
import eu.darken.porter.manager.ui.SettingsSwitch

/** Toggle states and already-resolved row labels; the activity owns the preference reads. */
internal data class StartupSettingsUiState(
    val startOnBoot: Boolean,
    val startOnBootEnabled: Boolean,
    val togglesBusy: Boolean = false,
    val watchdog: Boolean,
    val autoUpdateService: Boolean,
    val autoUpdateServiceEnabled: Boolean,
    val showPairingMethod: Boolean,
    val pairingMethodLabel: String,
    val showTcpPort: Boolean,
    val tcpPortLabel: String,
    val tcpPortNeedsRestart: Boolean,
    val showBatteryAction: Boolean = false,
    val showAlertsAction: Boolean = false,
)

internal data class StartupSettingsActions(
    val onBack: () -> Unit,
    val onStartOnBootChange: (Boolean) -> Unit,
    val onWatchdogChange: (Boolean) -> Unit,
    val onAutoUpdateServiceChange: (Boolean) -> Unit,
    val onPairingMethod: () -> Unit,
    val onTcpPort: () -> Unit,
    val onBatteryOptimization: () -> Unit,
    val onNotificationSettings: () -> Unit,
)

internal fun tcpPortIcon(needsRestart: Boolean): ImageVector =
    if (needsRestart) Icons.TwoTone.RestartAlt else Icons.TwoTone.Wifi

@Composable
internal fun StartupSettingsContent(state: StartupSettingsUiState, actions: StartupSettingsActions, modifier: Modifier = Modifier) {
    PorterScaffold(stringResource(R.string.porter_startup), onBack = actions.onBack) { padding ->
        Column(modifier.padding(padding).consumeWindowInsets(padding).verticalScroll(rememberScrollState())) {
            SettingsSwitch(stringResource(R.string.settings_start_on_boot), Icons.TwoTone.PlayArrow,
                state.startOnBoot, enabled = state.startOnBootEnabled && !state.togglesBusy,
                summary = if (state.startOnBootEnabled) null else stringResource(R.string.settings_start_on_boot_summary),
                onCheckedChange = actions.onStartOnBootChange)
            SettingsSwitch(stringResource(R.string.settings_watchdog), Icons.TwoTone.Autorenew,
                state.watchdog, stringResource(R.string.settings_watchdog_summary),
                enabled = !state.togglesBusy, onCheckedChange = actions.onWatchdogChange)
            SettingsSwitch(stringResource(R.string.porter_service_auto_update), Icons.TwoTone.SystemUpdate,
                state.autoUpdateService, enabled = state.autoUpdateServiceEnabled,
                summary = stringResource(if (state.autoUpdateServiceEnabled) R.string.porter_service_auto_update_summary else R.string.porter_service_update_primary),
                onCheckedChange = actions.onAutoUpdateServiceChange)
            if (state.showBatteryAction) SettingsItem(stringResource(R.string.porter_background_operation), Icons.TwoTone.Info,
                stringResource(R.string.snackbar_battery_optimization_home), onClick = actions.onBatteryOptimization)
            if (state.showAlertsAction) SettingsItem(stringResource(R.string.settings_alerts_blocked_title), Icons.TwoTone.NotificationsOff,
                stringResource(R.string.settings_alerts_blocked_summary), onClick = actions.onNotificationSettings)
            if (state.showPairingMethod) {
                SettingsItem(stringResource(R.string.porter_pairing_method), Icons.TwoTone.Link,
                    state.pairingMethodLabel, onClick = actions.onPairingMethod)
            }
            if (state.showTcpPort) {
                SettingsItem(stringResource(R.string.settings_tcp_port), tcpPortIcon(state.tcpPortNeedsRestart),
                    state.tcpPortLabel, onClick = actions.onTcpPort)
            }
        }
    }
}
