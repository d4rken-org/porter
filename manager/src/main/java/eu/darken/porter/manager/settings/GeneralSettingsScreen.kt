package eu.darken.porter.manager.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.AltRoute
import androidx.compose.material.icons.twotone.Contrast
import androidx.compose.material.icons.twotone.DarkMode
import androidx.compose.material.icons.twotone.NewReleases
import androidx.compose.material.icons.twotone.Palette
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import eu.darken.porter.manager.R
import eu.darken.porter.manager.ui.PorterScaffold
import eu.darken.porter.manager.ui.SettingsCategory
import eu.darken.porter.manager.ui.SettingsItem
import eu.darken.porter.manager.ui.SettingsSwitch

/** Already-resolved row labels; the activity owns the preference reads. */
internal data class GeneralSettingsUiState(
    val themeModeLabel: String,
    val themeStyleLabel: String,
    val themeColorLabel: String,
    val themeColorEnabled: Boolean,
    val updateCheckSupported: Boolean = false,
    val updateCheck: Boolean = false,
    val updateChannelLabel: String = "",
)

internal data class GeneralSettingsActions(
    val onBack: () -> Unit,
    val onThemeMode: () -> Unit,
    val onThemeStyle: () -> Unit,
    val onThemeColor: () -> Unit,
    val onUpdateCheckChange: (Boolean) -> Unit,
    val onUpdateChannel: () -> Unit,
)

@Composable
internal fun GeneralSettingsContent(state: GeneralSettingsUiState, actions: GeneralSettingsActions, modifier: Modifier = Modifier) {
    PorterScaffold(stringResource(R.string.settings_general), onBack = actions.onBack) { padding ->
        Column(modifier.padding(padding).consumeWindowInsets(padding).verticalScroll(rememberScrollState())) {
            SettingsCategory(stringResource(R.string.settings_user_interface))
            SettingsItem(stringResource(R.string.porter_theme_mode), Icons.TwoTone.DarkMode,
                state.themeModeLabel, onClick = actions.onThemeMode)
            SettingsItem(stringResource(R.string.porter_theme_style), Icons.TwoTone.Contrast,
                state.themeStyleLabel, onClick = actions.onThemeStyle)
            SettingsItem(stringResource(R.string.porter_theme_color), Icons.TwoTone.Palette,
                state.themeColorLabel, enabled = state.themeColorEnabled, onClick = actions.onThemeColor)
            if (state.updateCheckSupported) {
                SettingsCategory(stringResource(R.string.settings_updates))
                SettingsSwitch(stringResource(R.string.updater_check), Icons.TwoTone.NewReleases, state.updateCheck,
                    stringResource(R.string.updater_check_summary), onCheckedChange = actions.onUpdateCheckChange)
                SettingsItem(stringResource(R.string.updater_channel), Icons.AutoMirrored.TwoTone.AltRoute,
                    state.updateChannelLabel, enabled = state.updateCheck, onClick = actions.onUpdateChannel)
            }
        }
    }
}
