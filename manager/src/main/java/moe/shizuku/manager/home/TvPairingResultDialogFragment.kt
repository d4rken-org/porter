package moe.shizuku.manager.home

import android.os.Bundle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.TvPairingResult
import moe.shizuku.manager.adb.TvPairingResultStore
import moe.shizuku.manager.ui.ComposeDialogFragment

class TvPairingResultDialogFragment : ComposeDialogFragment() {
    @Composable override fun Content() {
        val success = requireArguments().getBoolean("success")
        TvPairingResultContent(success, requireArguments().getString("message").orEmpty(),
            onAction = {
                TvPairingResultStore(ShizukuSettings.getPreferences()).clear()
                dismissAllowingStateLoss()
                if (success) WirelessStart.start(requireActivity(), requireActivity().lifecycleScope)
                else WirelessStart.pair(requireContext())
            }, onClose = {
                TvPairingResultStore(ShizukuSettings.getPreferences()).clear()
                dismissAllowingStateLoss()
            })
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

@Composable
internal fun TvPairingResultContent(success: Boolean, message: String, onAction: () -> Unit, onClose: () -> Unit) {
    Text(stringResource(if (success) R.string.notification_adb_pairing_succeed_title
        else R.string.notification_adb_pairing_failed_title), style = MaterialTheme.typography.headlineSmall)
    Text(message)
    Button(onClick = onAction) {
        Text(stringResource(if (success) R.string.home_root_button_start else R.string.notification_adb_pairing_retry))
    }
    TextButton(onClick = onClose) { Text(stringResource(android.R.string.ok)) }
}
