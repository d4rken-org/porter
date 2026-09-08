package moe.shizuku.manager.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ui.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var start by rememberSaveable { mutableStateOf(true) }
    var regenerate by rememberSaveable { mutableStateOf(false) }
    var token by remember { mutableStateOf(ShizukuSettings.getAuthToken()) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.home_automation_bottom_sheet_intents), style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterChip(start, { start = true }, { Text(stringResource(R.string.start)) })
                FilterChip(!start, { start = false }, { Text(stringResource(R.string.stop)) })
            }
            listOf(
                R.string.home_automation_bottom_sheet_label_action to "${BuildConfig.APPLICATION_ID}.${if (start) "START" else "STOP"}",
                R.string.home_automation_bottom_sheet_label_package to BuildConfig.APPLICATION_ID,
                R.string.home_automation_bottom_sheet_label_target to "Broadcast Receiver",
                R.string.home_automation_bottom_sheet_label_extras to "auth: $token"
            ).forEach { (label, value) ->
                OutlinedTextField(value, {}, Modifier.fillMaxWidth(), readOnly = true, label = { Text(stringResource(label)) }, trailingIcon = {
                    TextButton(onClick = { copyText(context, if (label == R.string.home_automation_bottom_sheet_label_extras) token else value) }) {
                        Text(stringResource(android.R.string.copy))
                    }
                })
            }
            TextButton(onClick = { regenerate = true }) { Text(stringResource(R.string.home_automation_regenerate_token)) }
        }
    }
    if (regenerate) MessageDialog(stringResource(R.string.home_automation_regenerate_token), stringResource(R.string.home_automation_regenerate_token_message),
        { regenerate = false }, onConfirm = { token = ShizukuSettings.generateAuthToken(); regenerate = false })
}
