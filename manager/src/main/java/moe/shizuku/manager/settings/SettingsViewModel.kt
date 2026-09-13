package moe.shizuku.manager.settings

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.SettingsHelper

class SettingsViewModel @JvmOverloads constructor(
    application: Application,
    private val savedState: SavedStateHandle,
    private val rootProbe: suspend () -> Boolean = { withContext(Dispatchers.IO) { EnvironmentUtils.isRooted() } },
) : AndroidViewModel(application) {
    val dialog: StateFlow<String?> = savedState.getStateFlow("dialog", null)
    val pendingSetting: StateFlow<String?> = savedState.getStateFlow("pendingSetting", null)

    private val bootCapable = MutableStateFlow<Boolean?>(null)

    /** `null` until the probe below resolves, which leaves the start-on-boot toggle disabled. */
    val canBoot: StateFlow<Boolean?> = bootCapable.asStateFlow()

    init {
        // The root probe blocks on shell initialisation and can wait on a root prompt, so the
        // cheap answers are taken first and the probe itself never runs on a UI thread.
        if (Build.VERSION.SDK_INT >= 30 || EnvironmentUtils.isTelevision()) {
            bootCapable.value = true
        } else {
            viewModelScope.launch {
                bootCapable.value = try {
                    rootProbe()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    false
                }
            }
        }
    }

    fun show(value: String?) { savedState["dialog"] = value }

    fun setAutoUpdateService(enabled: Boolean) {
        if (moe.shizuku.manager.utils.UserHandleCompat.myUserId() != 0) return
        ShizukuSettings.getPreferences().edit().putBoolean(ShizukuSettings.Keys.KEY_AUTO_UPDATE_SERVICE, enabled).apply()
        if (!enabled) moe.shizuku.manager.worker.ServiceUpdateWorker.cancel(getApplication())
    }

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
