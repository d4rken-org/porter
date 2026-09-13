package moe.shizuku.manager.home

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.shizuku.manager.R
import moe.shizuku.manager.ui.ComposeDialogFragment
import moe.shizuku.manager.utils.SettingsPage

class WadbEnableUsbDebuggingDialogFragment : ComposeDialogFragment() {
    @Composable override fun Content() {
        Text(stringResource(R.string.dialog_usb_debugging_not_enabled))
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable override fun Actions() {
        FlowRow(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { SettingsPage.Developer.HighlightUsbDebugging.launch(requireContext()); dismissAllowingStateLoss() }) { Text(stringResource(R.string.development_settings)) }
            TextButton(onClick = { dismissAllowingStateLoss() }) { Text(stringResource(android.R.string.cancel)) }
        }
    }
}
