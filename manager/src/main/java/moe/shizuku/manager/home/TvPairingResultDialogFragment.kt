package moe.shizuku.manager.home

import android.content.Context
import android.os.Bundle
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.TvPairingResult
import moe.shizuku.manager.adb.TvPairingResultStore
import moe.shizuku.manager.ui.ComposeDialogFragment
import moe.shizuku.manager.utils.SettingsPage

class TvPairingResultDialogFragment : ComposeDialogFragment() {
    @Composable override fun Content() {
        val context = requireContext()
        val (serviceEnabled, cleanupDelayed) = rememberTvPairingCleanupState(context)
        val success = requireArguments().getBoolean("success")
        TvPairingResultContent(success, requireArguments().getString("message").orEmpty(),
            onAction = {
                TvPairingResultStore(ShizukuSettings.getPreferences()).clear()
                dismissAllowingStateLoss()
                if (success) WirelessStart.start(requireActivity(), requireActivity().lifecycleScope)
                else WirelessStart.pair(requireContext())
            }, onClose = {
                if (!context.isAccessibilityEnabled()) TvPairingResultStore(ShizukuSettings.getPreferences()).clear()
                dismissAllowingStateLoss()
            }, serviceEnabled = serviceEnabled, cleanupDelayed = cleanupDelayed,
            onAccessibilitySettings = { SettingsPage.Accessibility.launch(context) })
    }

    companion object {
        internal fun create(result: TvPairingResult) = TvPairingResultDialogFragment().apply {
            arguments = Bundle().apply {
                putBoolean("success", result.success)
                putString("message", result.message)
            }
        }
    }
}

internal data class TvPairingCleanupState(val serviceEnabled: Boolean, val cleanupDelayed: Boolean)

@Composable
internal fun rememberTvPairingCleanupState(context: Context): TvPairingCleanupState {
    var serviceEnabled by remember { mutableStateOf(context.isAccessibilityEnabled()) }
    var cleanupDelayed by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                serviceEnabled = context.isAccessibilityEnabled()
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, observer)
        serviceEnabled = context.isAccessibilityEnabled()
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    LaunchedEffect(serviceEnabled) {
        cleanupDelayed = false
        if (serviceEnabled) {
            delay(5_000)
            cleanupDelayed = true
        }
    }
    return TvPairingCleanupState(serviceEnabled, cleanupDelayed)
}

@Composable
internal fun TvPairingResultContent(
    success: Boolean,
    message: String,
    onAction: () -> Unit,
    onClose: () -> Unit,
    serviceEnabled: Boolean = false,
    cleanupDelayed: Boolean = false,
    onAccessibilitySettings: () -> Unit = {},
) {
    Text(stringResource(if (success) R.string.notification_adb_pairing_succeed_title
        else R.string.notification_adb_pairing_failed_title), style = MaterialTheme.typography.headlineSmall)
    Text(message)
    Text(stringResource(when {
        !serviceEnabled -> R.string.porter_tv_pairing_complete
        cleanupDelayed -> R.string.porter_tv_pairing_cleanup_delayed
        else -> R.string.porter_tv_pairing_disabling
    }))
    if (serviceEnabled && cleanupDelayed) {
        TextButton(onClick = onAccessibilitySettings) { Text(stringResource(R.string.porter_tv_pairing_open_accessibility)) }
    }
    val actionFocus = remember { FocusRequester() }
    LaunchedEffect(serviceEnabled) {
        if (!serviceEnabled) actionFocus.requestFocus()
    }
    Button(onClick = onAction, enabled = !serviceEnabled, modifier = Modifier.focusRequester(actionFocus)) {
        Text(stringResource(if (success) R.string.home_root_button_start else R.string.notification_adb_pairing_retry))
    }
    TextButton(onClick = onClose) { Text(stringResource(android.R.string.ok)) }
}
