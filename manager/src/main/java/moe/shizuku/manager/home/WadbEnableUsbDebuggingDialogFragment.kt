package moe.shizuku.manager.home

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import moe.shizuku.manager.R
import moe.shizuku.manager.ui.ComposeDialogFragment
import moe.shizuku.manager.utils.SettingsPage

class WadbEnableUsbDebuggingDialogFragment : ComposeDialogFragment() {
    @Composable override fun Content() {
        Text(stringResource(R.string.dialog_usb_debugging_not_enabled))
        TextButton(onClick = { SettingsPage.Developer.HighlightUsbDebugging.launch(requireContext()); dismissAllowingStateLoss() }) { Text(stringResource(R.string.development_settings)) }
        TextButton(onClick = { dismissAllowingStateLoss() }) { Text(stringResource(android.R.string.cancel)) }
    }
}
