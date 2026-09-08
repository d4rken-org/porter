package moe.shizuku.manager.ui

import android.content.SharedPreferences
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.app.AppActivity

@Composable
fun preferencesRevision(): Int {
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val preferences = ShizukuSettings.getPreferences()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return revision
}

@Composable
fun PorterTheme(content: @Composable () -> Unit) {
    val revision = preferencesRevision()
    val preferences = ShizukuSettings.getPreferences()
    val mode = remember(revision) { ShizukuSettings.getNightMode() }
    val style = remember(revision) { preferences.getString(ShizukuSettings.Keys.KEY_THEME_STYLE, "DEFAULT") }
    val color = remember(revision) { preferences.getString(ShizukuSettings.Keys.KEY_THEME_COLOR, "BLUE") }
    val dark = when (mode) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val context = LocalContext.current
    val schemes = when (color) {
        "GREEN" -> listOf(PorterColorsGreen.LightDefault, PorterColorsGreen.DarkDefault, PorterColorsGreen.LightMediumContrast, PorterColorsGreen.DarkMediumContrast, PorterColorsGreen.LightHighContrast, PorterColorsGreen.DarkHighContrast)
        "AMOLED" -> listOf(PorterColorsAmoled.LightDefault, PorterColorsAmoled.DarkDefault, PorterColorsAmoled.LightMediumContrast, PorterColorsAmoled.DarkMediumContrast, PorterColorsAmoled.LightHighContrast, PorterColorsAmoled.DarkHighContrast)
        else -> listOf(PorterColorsBlue.LightDefault, PorterColorsBlue.DarkDefault, PorterColorsBlue.LightMediumContrast, PorterColorsBlue.DarkMediumContrast, PorterColorsBlue.LightHighContrast, PorterColorsBlue.DarkHighContrast)
    }
    val scheme = if (style == "MATERIAL_YOU" && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else schemes[(when (style) { "MEDIUM_CONTRAST" -> 2; "HIGH_CONTRAST" -> 4; else -> 0 }) + if (dark) 1 else 0]
    val view = LocalView.current
    SideEffect {
        (context as? AppActivity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
