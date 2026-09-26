package eu.darken.porter.manager.home

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.CoroutineScope
import eu.darken.porter.manager.Helps
import eu.darken.porter.manager.PorterSettings
import eu.darken.porter.manager.R
import eu.darken.porter.manager.adb.AdbPairingTutorialActivity
import eu.darken.porter.manager.home.showAccessibilityDialog
import eu.darken.porter.manager.ktx.asActivity
import eu.darken.porter.manager.receiver.NotifCancelReceiver
import eu.darken.porter.manager.starter.StarterActivity
import eu.darken.porter.manager.utils.CustomTabsHelper
import eu.darken.porter.manager.utils.EnvironmentUtils
import eu.darken.porter.manager.utils.LOGGER
import eu.darken.porter.manager.utils.PorterStateMachine
import eu.darken.porter.manager.utils.SettingsHelper

internal enum class StartRoute { USB_DEBUGGING_OFF, WIRELESS_DEBUGGING_UNAVAILABLE, DISCOVER, DIRECT_PORT }

internal fun startRoute(adbEnabled: Int, adbEnabledTrusted: Boolean, tcpPort: Int, tlsSupported: Boolean): StartRoute = when {
    adbEnabled == 0 && adbEnabledTrusted -> StartRoute.USB_DEBUGGING_OFF
    // ADB is not listening on a TCP port and the device can't do TLS: wireless debugging is unavailable
    tcpPort <= 0 && !tlsSupported -> StartRoute.WIRELESS_DEBUGGING_UNAVAILABLE
    // ADB is not listening on a TCP port but TLS is supported: discover the port over mDNS
    tcpPort <= 0 -> StartRoute.DISCOVER
    // ADB is already listening on a TCP port: use it as-is
    else -> StartRoute.DIRECT_PORT
}

object WirelessStart {
        fun start (context: Context, scope: CoroutineScope) {
            if (PorterStateMachine.instance.get() == PorterStateMachine.State.STARTING) {
                LOGGER.i("Wireless start: ignored, already starting")
                Toast.makeText(context, context.getString(R.string.toast_shizuku_already_starting), Toast.LENGTH_SHORT).show()
                return
            }

            context.sendBroadcast(Intent(context, NotifCancelReceiver::class.java))

            val cr = context.contentResolver
            val wss = context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
            val write = when {
                !wss -> "skipped"
                SettingsHelper.tryPutGlobalInt(cr, Settings.Global.ADB_ENABLED, 1) -> "ok"
                else -> "failed"
            }

            val adbEnabled = Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0)
            // Android 17 can report ADB_ENABLED as 0 to apps while USB debugging is on.
            val adbEnabledTrusted = Build.VERSION.SDK_INT < 37
            val tcpPort = EnvironmentUtils.getAdbTcpPort()
            val tlsSupported = EnvironmentUtils.isTlsSupported()
            val route = startRoute(adbEnabled, adbEnabledTrusted, tcpPort, tlsSupported)
            LOGGER.i("Wireless start: route=%s adbEnabled=%d trusted=%b wss=%b write=%s tcpPort=%d tls=%b",
                route, adbEnabled, adbEnabledTrusted, wss, write, tcpPort, tlsSupported)

            when (route) {
                StartRoute.USB_DEBUGGING_OFF ->
                    WadbEnableUsbDebuggingDialogFragment().show(context.asActivity<FragmentActivity>().supportFragmentManager)
                StartRoute.WIRELESS_DEBUGGING_UNAVAILABLE ->
                    WadbNotEnabledDialogFragment().show(context.asActivity<FragmentActivity>().supportFragmentManager)
                StartRoute.DISCOVER ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        AdbDialogFragment().show(context.asActivity<FragmentActivity>().supportFragmentManager)
                    }
                StartRoute.DIRECT_PORT -> {
                    val intent = Intent(context, StarterActivity::class.java).apply {
                        putExtra(StarterActivity.EXTRA_PORT, tcpPort)
                    }
                    context.startActivity(intent)
                }
            }
        }
    @RequiresApi(Build.VERSION_CODES.R)
    fun pair(context: Context) {
        if (EnvironmentUtils.isTelevision()) {
            context.showAccessibilityDialog()
        } else if ((context.display?.displayId ?: -1) > 0 || PorterSettings.legacyPairing) {
            // Running in a multi-display environment (e.g., Windows Subsystem for Android),
            // pairing dialog can be displayed simultaneously with Shizuku.
            // Input from notification is harder to use under this situation.
            AdbPairDialogFragment().show(context.asActivity<FragmentActivity>().supportFragmentManager)
        } else {
            context.startActivity(Intent(context, AdbPairingTutorialActivity::class.java))
        }
    }
}
