package moe.shizuku.manager.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import moe.shizuku.manager.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PorterScaffold(title: String, onBack: (() -> Unit)? = null, subtitle: String? = null,
                   @DrawableRes titleIcon: Int? = null,
                   actions: @Composable RowScope.() -> Unit = {}, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    titleIcon?.let { Image(painterResource(it), contentDescription = null, modifier = Modifier.size(44.dp)) }
                    Column(Modifier.weight(1f)) {
                        Text(title)
                        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }, navigationIcon = {
                onBack?.let { back ->
                    IconButton(onClick = back) { Icon(painterResource(R.drawable.ic_arrow_back_24dp), stringResource(R.string.porter_navigate_back)) }
                }
            }, actions = actions, windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
        }, content = content
    )
}

@Composable
fun SettingsCategory(title: String) {
    Text(title, Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
}

@Composable
fun SettingsItem(title: String, @DrawableRes icon: Int, summary: String? = null,
                 enabled: Boolean = true, onClick: () -> Unit, trailing: @Composable (() -> Unit)? = null) {
    SettingsRow(title, icon, summary, enabled, Modifier.clickable(enabled = enabled, onClick = onClick), trailing)
}

@Composable
fun SettingsSwitch(title: String, @DrawableRes icon: Int, checked: Boolean, summary: String? = null,
                   enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    SettingsRow(title, icon, summary, enabled, Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)) {
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun SettingsRow(title: String, icon: Int, summary: String?, enabled: Boolean, modifier: Modifier,
                        trailing: @Composable (() -> Unit)?) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else .38f)
    Row(modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(painterResource(icon), null, Modifier.size(24.dp), tint = color)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .38f))
            summary?.takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = color) }
        }
        trailing?.invoke()
    }
}

@Composable
fun ChoiceDialog(title: String, choices: List<String>, selected: Int, onDismiss: () -> Unit, onSelect: (Int) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            choices.forEachIndexed { index, label ->
                Row(Modifier.fillMaxWidth().selectable(index == selected, onClick = { onSelect(index) }, role = Role.RadioButton)
                    .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(index == selected, onClick = null)
                    Text(label, Modifier.padding(start = 16.dp))
                }
            }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } })
}

@Composable
fun MessageDialog(title: String, message: String, onDismiss: () -> Unit,
                  confirm: String = stringResource(android.R.string.ok), confirmColor: Color = MaterialTheme.colorScheme.primary,
                  onConfirm: () -> Unit = onDismiss) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { Text(message, Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = onConfirm, colors = ButtonDefaults.textButtonColors(contentColor = confirmColor)) { Text(confirm) } },
        dismissButton = if (onConfirm !== onDismiss) {{ TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } }} else null)
}
