package eu.darken.porter.manager.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.HelpOutline
import androidx.compose.material.icons.twotone.Apps
import androidx.compose.material.icons.twotone.Code
import androidx.compose.material.icons.twotone.Favorite
import androidx.compose.material.icons.twotone.Info
import androidx.compose.material.icons.twotone.IntegrationInstructions
import androidx.compose.material.icons.twotone.PowerSettingsNew
import androidx.compose.material.icons.twotone.Terminal
import androidx.compose.material.icons.twotone.Tune
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import eu.darken.porter.manager.R
import eu.darken.porter.manager.ui.PorterScaffold
import eu.darken.porter.manager.ui.SettingsCategory
import eu.darken.porter.manager.ui.SettingsItem

/** What the index summarises; the subscreens own their own reads. */
internal data class SettingsUiState(
    val versionName: String,
    val updateCheckSupported: Boolean = false,
    val startupNeedsAttention: Boolean = false,
)

internal data class SettingsActions(
    val onBack: () -> Unit,
    val onGeneral: () -> Unit,
    val onStartup: () -> Unit,
    val onCompatibility: () -> Unit,
    val onTerminal: () -> Unit,
    val onAutomation: () -> Unit,
    val onDeveloperGuide: () -> Unit,
    val onSupport: () -> Unit,
    val onAcknowledgements: () -> Unit,
    val onVersion: () -> Unit,
)

@Composable
internal fun SettingsScreenContent(state: SettingsUiState, actions: SettingsActions, modifier: Modifier = Modifier) {
    PorterScaffold(stringResource(R.string.settings_title), onBack = actions.onBack) { padding ->
        Column(modifier.padding(padding).consumeWindowInsets(padding).verticalScroll(rememberScrollState())) {
            SettingsItem(stringResource(R.string.settings_general), Icons.TwoTone.Tune,
                stringResource(if (state.updateCheckSupported) R.string.settings_general_summary else R.string.settings_general_summary_theme),
                onClick = actions.onGeneral)
            SettingsItem(stringResource(R.string.porter_startup), Icons.TwoTone.PowerSettingsNew,
                stringResource(if (state.startupNeedsAttention) R.string.settings_startup_attention else R.string.settings_startup_summary),
                onClick = actions.onStartup)
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
