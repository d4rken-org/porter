package moe.shizuku.manager.home

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.shizuku.manager.Manifest
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.model.ServiceStatus
import moe.shizuku.manager.support.ServerDiagnostics
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.Logger.LOGGER
import moe.shizuku.manager.utils.SettingsHelper
import moe.shizuku.manager.utils.ShizukuStateMachine
import moe.shizuku.manager.utils.ShizukuSystemApis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rikka.shizuku.Shizuku

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext: Context = getApplication<Application>().applicationContext

    private val mutableStatus = MutableStateFlow(ServiceStatus())
    val serviceStatus = mutableStatus.asStateFlow()
    val shouldShowBatteryOptimizationSnackbar = MutableStateFlow(false)
    val shouldShowRebootDialog = MutableStateFlow(false)
    val shouldShowUninstallDialog = MutableStateFlow(false)
    private val reloadMutex = Mutex()

    private fun load(): ServiceStatus {
        // In certain cases when user re-installs Shizuku with different package name (e.g., when using stealth mode), the system doesn't recognize the Shizuku permission.
        // As a result, all permission operations (check/grant/revoke) will fail.
        // This is fixed by rebooting the device.
        // Run getPermissionGroupInfo() to trigger the exception. Then catch it and show a dialog prompting the user to reboot their device.
        try {
            val permissionGroup = appContext.packageManager.getPermissionGroupInfo(Manifest.permission_group.API, 0)
            val permission = appContext.packageManager.getPermissionInfo(Manifest.permission.API_V23, 0)
            if (permission.packageName != appContext.packageName) {
                shouldShowUninstallDialog.value = true
            }
        } catch (e: PackageManager.NameNotFoundException) {
            shouldShowRebootDialog.value = true
        }

        if (!ShizukuStateMachine.isRunning()) {
            return ServiceStatus()
        }

        val uid = Shizuku.getUid()
        val apiVersion = Shizuku.getVersion()
        val patchVersion = Shizuku.getServerPatchVersion().let { if (it < 0) 0 else it }
        val seContext = if (apiVersion >= 6) {
            try {
                Shizuku.getSELinuxContext()
            } catch (tr: Throwable) {
                LOGGER.w(tr, "getSELinuxContext")
                null
            }
        } else null
        val permissionTest =
            Shizuku.checkRemotePermission("android.permission.GRANT_RUNTIME_PERMISSIONS") == PackageManager.PERMISSION_GRANTED

        // Before a526d6bb, server will not exit on uninstall, manager installed later will get not permission
        // Run a random remote transaction here, report no permission as not running
        ShizukuSystemApis.checkPermission(Manifest.permission.API_V23, appContext.packageName, 0)
        val porterVersion = try {
            Shizuku.getBinder()?.let { ServerDiagnostics.readInfo(it)?.version }
        } catch (e: Exception) {
            LOGGER.w(e, "Read Porter service version")
            null
        }
        return ServiceStatus(uid, apiVersion, patchVersion, seContext, permissionTest, porterVersion)
    }

    fun reload() {
        viewModelScope.launch(Dispatchers.IO) {
            reloadMutex.withLock {
                try { mutableStatus.value = load() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { LOGGER.w(e, "Load service status"); mutableStatus.value = ServiceStatus() }
            }
        }
    }

    fun checkBatteryOptimization() {
        if (EnvironmentUtils.isTelevision()) return
        if (!ShizukuSettings.getStartOnBoot(appContext) && !ShizukuSettings.getWatchdog()) { shouldShowBatteryOptimizationSnackbar.value = false; return }
        shouldShowBatteryOptimizationSnackbar.value = !SettingsHelper.isIgnoringBatteryOptimizations(appContext)
    }

}
