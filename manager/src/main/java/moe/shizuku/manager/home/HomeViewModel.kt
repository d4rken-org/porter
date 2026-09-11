package moe.shizuku.manager.home

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.shizuku.manager.Manifest
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.SettingsHelper
import kotlinx.coroutines.flow.MutableStateFlow

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext: Context = getApplication<Application>().applicationContext

    val shouldShowBatteryOptimizationSnackbar = MutableStateFlow(false)
    val shouldShowRebootDialog = MutableStateFlow(false)
    val shouldShowUninstallDialog = MutableStateFlow(false)

    private fun load() {
        // In certain cases when user re-installs Shizuku with different package name (e.g., when using stealth mode), the system doesn't recognize the Shizuku permission.
        // As a result, all permission operations (check/grant/revoke) will fail.
        // This is fixed by rebooting the device.
        // Run getPermissionGroupInfo() to trigger the exception. Then catch it and show a dialog prompting the user to reboot their device.
        try {
            appContext.packageManager.getPermissionGroupInfo(Manifest.permission_group.API, 0)
            val permission = appContext.packageManager.getPermissionInfo(Manifest.permission.API_V23, 0)
            if (permission.packageName != appContext.packageName) {
                shouldShowUninstallDialog.value = true
            }
        } catch (e: PackageManager.NameNotFoundException) {
            shouldShowRebootDialog.value = true
        }

    }

    fun reload() {
        viewModelScope.launch(Dispatchers.IO) { load() }
    }

    fun checkBatteryOptimization() {
        if (EnvironmentUtils.isTelevision()) return
        if (!ShizukuSettings.getStartOnBoot(appContext) && !ShizukuSettings.getWatchdog()) { shouldShowBatteryOptimizationSnackbar.value = false; return }
        shouldShowBatteryOptimizationSnackbar.value = !SettingsHelper.isIgnoringBatteryOptimizations(appContext)
    }

}
