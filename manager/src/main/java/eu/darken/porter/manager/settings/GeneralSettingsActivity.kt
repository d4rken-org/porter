package eu.darken.porter.manager.settings

import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.darken.porter.manager.PorterSettings
import eu.darken.porter.manager.R
import eu.darken.porter.manager.ui.*
import eu.darken.porter.manager.updater.UpdateChannel
import eu.darken.porter.manager.updater.UpdateRepository

class GeneralSettingsActivity : ComposeActivity() {
    private val updates by lazy { UpdateRepository.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        porterContent { GeneralSettingsScreen() }
    }

    @Composable
    private fun GeneralSettingsScreen() {
        val revision = preferencesRevision()
        val preferences = PorterSettings.preferences
        val values = remember(revision) { preferences.all }
        val updateState by updates.state.collectAsStateWithLifecycle()
        var dialog by rememberSaveable { mutableStateOf<String?>(null) }
        val mode = (values[PorterSettings.Keys.KEY_NIGHT_MODE] as? Int ?: -1).toString()
        val style = values[PorterSettings.Keys.KEY_THEME_STYLE] as? String ?: "DEFAULT"
        val color = values[PorterSettings.Keys.KEY_THEME_COLOR] as? String ?: "BLUE"
        val modes = stringArrayResource(R.array.night_mode).toList()
        val modeValues = resources.getIntArray(R.array.night_mode_value).map(Int::toString)
        val allStyles = stringArrayResource(R.array.porter_theme_styles).toList()
        val allStyleValues = resources.getStringArray(R.array.porter_theme_style_values).toList()
        val styleIndices = allStyleValues.indices.filter { Build.VERSION.SDK_INT >= 31 || allStyleValues[it] != "MATERIAL_YOU" }
        val styles = styleIndices.map { allStyles[it] }
        val styleValues = styleIndices.map { allStyleValues[it] }
        val colors = stringArrayResource(R.array.porter_theme_colors).toList()
        val colorValues = resources.getStringArray(R.array.porter_theme_color_values).toList()
        GeneralSettingsContent(
            GeneralSettingsUiState(
                themeModeLabel = modes.getOrElse(modeValues.indexOf(mode)) { modes.first() },
                themeStyleLabel = styles.getOrElse(styleValues.indexOf(style)) { styles.first() },
                themeColorLabel = if (style == "MATERIAL_YOU" && Build.VERSION.SDK_INT >= 31) stringResource(R.string.porter_theme_color_system)
                    else colors.getOrElse(colorValues.indexOf(color)) { colors.first() },
                themeColorEnabled = style != "MATERIAL_YOU" || Build.VERSION.SDK_INT < 31,
                updateCheckSupported = updateState.supported,
                updateCheck = updateState.enabled,
                updateChannelLabel = stringResource(when (updateState.channel) {
                    UpdateChannel.PRODUCTION -> R.string.updater_channel_production
                    UpdateChannel.BETA -> R.string.updater_channel_beta
                }),
            ),
            GeneralSettingsActions(
                onBack = { finish() },
                onThemeMode = { dialog = "mode" },
                onThemeStyle = { dialog = "style" },
                onThemeColor = { dialog = "color" },
                onUpdateCheckChange = { updates.setEnabled(it) },
                onUpdateChannel = { dialog = "update_channel" },
            ),
        )
        val dismiss = { dialog = null }
        when (dialog) {
            "mode" -> ChoiceDialog(stringResource(R.string.porter_theme_mode), modes, modeValues.indexOf(mode), dismiss) {
                val value = modeValues[it].toInt()
                preferences.edit().putInt(PorterSettings.Keys.KEY_NIGHT_MODE, value).apply()
                dialog = null
                AppCompatDelegate.setDefaultNightMode(value)
            }
            "style", "color" -> {
                val isStyle = dialog == "style"
                ChoiceDialog(stringResource(if (isStyle) R.string.porter_theme_style else R.string.porter_theme_color),
                    if (isStyle) styles else colors, if (isStyle) styleValues.indexOf(style) else colorValues.indexOf(color), dismiss) {
                    preferences.edit().putString(if (isStyle) PorterSettings.Keys.KEY_THEME_STYLE else PorterSettings.Keys.KEY_THEME_COLOR,
                        if (isStyle) styleValues[it] else colorValues[it]).apply()
                    dialog = null
                }
            }
            "update_channel" -> ChoiceDialog(stringResource(R.string.updater_channel),
                listOf(stringResource(R.string.updater_channel_production_choice), stringResource(R.string.updater_channel_beta_choice)),
                UpdateChannel.entries.indexOf(updateState.channel), dismiss) {
                updates.setChannel(UpdateChannel.entries[it])
                dialog = null
            }
        }
    }
}
