package moe.shizuku.manager.settings

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.StateFlow
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.SettingsHelper

class SettingsViewModel(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {
    val dialog: StateFlow<String?> = savedState.getStateFlow("dialog", null)
    val pendingSetting: StateFlow<String?> = savedState.getStateFlow("pendingSetting", null)
    fun show(value: String?) { savedState["dialog"] = value }

    fun toggle(key: String, enabled: Boolean) {
        savedState["pendingSetting"] = key
        if (enabled && key == ShizukuSettings.Keys.KEY_START_ON_BOOT && !EnvironmentUtils.isTelevision() && Build.VERSION.SDK_INT < 33) {
            show("boot_warning")
        } else checkBattery(enabled)
    }

    fun checkBattery(enabled: Boolean = true) {
        if (enabled && !EnvironmentUtils.isTelevision() && !SettingsHelper.isIgnoringBatteryOptimizations(getApplication())) {
            show("battery")
        } else applyToggle(enabled)
    }

    fun batteryResult() {
        if (SettingsHelper.isIgnoringBatteryOptimizations(getApplication())) applyToggle(true)
        else cancelToggle()
    }

    fun cancelToggle() { savedState["pendingSetting"] = null; show(null) }
    private fun applyToggle(enabled: Boolean) {
        when (pendingSetting.value) {
            ShizukuSettings.Keys.KEY_START_ON_BOOT -> ShizukuSettings.setStartOnBoot(getApplication(), enabled)
            ShizukuSettings.Keys.KEY_WATCHDOG -> ShizukuSettings.setWatchdog(getApplication(), enabled)
        }
        cancelToggle()
    }
}
