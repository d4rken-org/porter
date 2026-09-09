package moe.shizuku.manager.adb

import android.Manifest
import android.app.AppOpsManager
import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.view.isGone
import androidx.core.view.isVisible
import moe.shizuku.manager.AppConstants

import moe.shizuku.manager.utils.SettingsHelper
import moe.shizuku.manager.utils.SettingsPage
import rikka.compatibility.DeviceCompatibility

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.shizuku.manager.R
import moe.shizuku.manager.ui.*

@RequiresApi(Build.VERSION_CODES.R)
class AdbPairingTutorialActivity : ComposeActivity() {
    private var notificationEnabled by mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationEnabled = isNotificationEnabled()
        if (notificationEnabled && savedInstanceState == null) startPairingService()
        porterContent {
            PorterScaffold(stringResource(R.string.adb_pairing), onBack = { finish() }) { padding ->
                Column(Modifier.padding(padding).consumeWindowInsets(padding).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (notificationEnabled) {
                        HtmlText(stringResource(R.string.adb_pairing_tutorial_content_notification))
                        HtmlText(stringResource(R.string.adb_pairing_tutorial_content_network))
                        HtmlText(stringResource(R.string.adb_pairing_tutorial_content_network_limation_not_foreground))
                    } else {
                        HtmlText(stringResource(R.string.adb_pairing_tutorial_content_notification_blocked))
                        Button(onClick = { SettingsPage.Notifications.NotificationSettings.launch(this@AdbPairingTutorialActivity) }) { Text(stringResource(R.string.notification_settings)) }
                    }
                    if (DeviceCompatibility.isMiui()) {
                        HtmlText(stringResource(R.string.adb_pairing_tutorial_content_miui))
                        HtmlText(stringResource(R.string.adb_pairing_tutorial_content_miui_2))
                    }
                    if (notificationEnabled) {
                        HtmlText(stringResource(R.string.adb_pairing_tutorial_content_steps))
                        Button(onClick = { SettingsHelper.launchOrHighlightWirelessDebugging(this@AdbPairingTutorialActivity) }) { Text(stringResource(R.string.development_settings)) }
                        HtmlText(stringResource(R.string.adb_pairing_tutorial_content_enter_pairing_code))
                        HtmlText(stringResource(R.string.adb_pairing_tutorial_content_finish))
                        Text(stringResource(R.string.porter_pairing_retry_instructions))
                        OutlinedButton(onClick = { startPairingService() }) {
                            Text(stringResource(R.string.porter_pairing_restart))
                        }
                    }
                }
            }
        }
    }
    private fun isNotificationEnabled(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = nm.getNotificationChannel(AdbPairingService.NOTIFICATION_CHANNEL)
        return nm.areNotificationsEnabled() && (channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE)
    }
    override fun onResume() {
        super.onResume()
        val enabled = isNotificationEnabled()
        if (enabled != notificationEnabled) { notificationEnabled = enabled; if (enabled) startPairingService() }
    }

    // Android 17 (SDK 37) gates local-network access behind ACCESS_LOCAL_NETWORK;
    // Android 16 (SDK 36) uses NEARBY_WIFI_DEVICES. Without a runtime grant the OS
    // intercepts the pairing connection with an endless "choose a device" picker.
    private fun localNetworkPermission(): String? = when {
        Build.VERSION.SDK_INT >= 37 -> "android.permission.ACCESS_LOCAL_NETWORK"
        Build.VERSION.SDK_INT >= 36 -> Manifest.permission.NEARBY_WIFI_DEVICES
        else -> null
    }

    private val localNetworkPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Start pairing whether or not the grant succeeded; a denial simply means
            // discovery/connect will fail and the service surfaces the error.
            doStartPairingService()
        }

    private fun startPairingService() {
        val permission = localNetworkPermission()
        if (permission != null && checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            localNetworkPermissionLauncher.launch(permission)
        } else {
            doStartPairingService()
        }
    }

    private fun doStartPairingService() {
        val intent = AdbPairingService.startIntent(this)
        try {
            startForegroundService(intent)
        } catch (e: Throwable) {
            Log.e(AppConstants.TAG, "startForegroundService", e)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && e is ForegroundServiceStartNotAllowedException
            ) {
                val mode = getSystemService(AppOpsManager::class.java)
                    .noteOpNoThrow("android:start_foreground", android.os.Process.myUid(), packageName, null, null)
                if (mode == AppOpsManager.MODE_ERRORED) {
                    Toast.makeText(this, "OP_START_FOREGROUND is denied. What are you doing?", Toast.LENGTH_LONG).show()
                }
                startService(intent)
            }
        }
    }
}
