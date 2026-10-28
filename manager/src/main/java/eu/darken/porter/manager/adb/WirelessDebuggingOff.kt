package eu.darken.porter.manager.adb

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import eu.darken.porter.manager.PorterSettings
import eu.darken.porter.manager.utils.LOGGER
import eu.darken.porter.manager.utils.SettingsHelper
import eu.darken.porter.sdk.Porter
import eu.darken.porter.sdk.PorterConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

internal const val ADB_WIFI_ENABLED = "adb_wifi_enabled"

internal const val GRANT_WAIT_MS = 10_000L

/**
 * Turns wireless debugging off after a start over it, when the user enabled that. Never throws
 * anything but cancellation.
 *
 * The server grants WRITE_SECURE_SETTINGS when this process attaches to it, which can come after
 * the service already counts as running, so a missing grant waits for [awaitConnection] once.
 */
suspend fun turnOffWirelessDebuggingAfterStart(
    context: Context,
    overWireless: Boolean,
    write: (ContentResolver, String, Int) -> Boolean = SettingsHelper::tryPutGlobalInt,
    awaitConnection: suspend () -> Unit = ::awaitPorterConnected,
) {
    try {
        if (!PorterSettings.wirelessDebuggingOff) {
            LOGGER.i("Wireless debugging off: skipped, setting is off")
            return
        }
        if (!overWireless) {
            LOGGER.i("Wireless debugging off: skipped, not started over wireless debugging")
            return
        }
        if (!canWriteSecureSettings(context)) {
            LOGGER.i("Wireless debugging off: waiting for WRITE_SECURE_SETTINGS")
            val connected = withTimeoutOrNull(GRANT_WAIT_MS) { awaitConnection() } != null
            if (!canWriteSecureSettings(context)) {
                LOGGER.w("Wireless debugging off: WRITE_SECURE_SETTINGS not granted, connected=%b", connected)
                return
            }
        }
        if (write(context.contentResolver, ADB_WIFI_ENABLED, 0)) {
            LOGGER.i("Wireless debugging off: written")
        } else {
            LOGGER.w("Wireless debugging off: write denied")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        LOGGER.e(e, "Wireless debugging off: failed")
    }
}

private suspend fun awaitPorterConnected() {
    Porter.state.first { it is PorterConnectionState.Connected }
}

private fun canWriteSecureSettings(context: Context): Boolean =
    context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
