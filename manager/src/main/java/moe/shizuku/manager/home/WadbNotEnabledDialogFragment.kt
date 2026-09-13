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

class WadbNotEnabledDialogFragment : ComposeDialogFragment() {
    @Composable override fun Content() {
        Text(stringResource(R.string.dialog_wireless_adb_not_enabled))
    }

    @Composable override fun Actions() {
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = { dismissAllowingStateLoss() }) { Text(stringResource(android.R.string.ok)) }
        }
    }
}
