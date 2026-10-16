package eu.darken.porter.manager.utils

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import eu.darken.porter.manager.BuildConfig
import eu.darken.porter.manager.utils.SettingsPage

object SettingsHelper {

    /** Android 17 can report ADB_ENABLED as 0 to apps while USB debugging is on. */
    fun isAdbEnabledSettingTrusted(): Boolean = Build.VERSION.SDK_INT < 37

    fun launchOrHighlightWirelessDebugging(context: Context) {
        val adbEnabled = Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0)
        wirelessDebuggingPage(adbEnabled, isAdbEnabledSettingTrusted()).launch(context)
    }

    internal fun wirelessDebuggingPage(adbEnabled: Int, adbEnabledTrusted: Boolean): SettingsPage.Developer =
        if (adbEnabled > 0 || !adbEnabledTrusted) SettingsPage.Developer.WirelessDebugging
        else SettingsPage.Developer.HighlightWirelessDebugging

    /** Returns false instead of throwing when the system denies the write. */
    fun tryPutGlobalInt(cr: ContentResolver, name: String, value: Int): Boolean =
        tryPutGlobalInt(name) { Settings.Global.putInt(cr, name, value) }

    internal fun tryPutGlobalInt(name: String, write: () -> Boolean): Boolean = try {
        write()
    } catch (e: SecurityException) {
        LOGGER.w(e, "Cannot write setting %s", name)
        false
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Falls back to Porter's app info page, where battery use can also be set, when the device lacks the screen.
     * Returns false when neither opens.
     */
    fun requestIgnoreBatteryOptimizations(context: Context, launcher: ActivityResultLauncher<Intent>? = null): Boolean {
        val appInfo = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        return listOf(batteryOptimizationIntent(context.packageName, BuildConfig.IS_FOSS), appInfo).any { intent ->
            try {
                if (launcher != null) launcher.launch(intent) else context.startActivity(intent)
                true
            } catch (e: ActivityNotFoundException) {
                false
            }
        }
    }

    /**
     * Google Play allows the direct exemption request only for listed app types, so the Play build opens the list:
     * https://developer.android.com/training/monitoring-device-state/doze-standby
     */
    internal fun batteryOptimizationIntent(packageName: String, foss: Boolean): Intent =
        if (foss) Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:$packageName"))
        else Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
}