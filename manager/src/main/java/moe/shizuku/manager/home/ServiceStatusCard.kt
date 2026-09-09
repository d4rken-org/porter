package moe.shizuku.manager.home

import android.text.BidiFormatter
import android.text.TextDirectionHeuristics
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.R
import moe.shizuku.manager.model.PorterServiceVersion
import moe.shizuku.manager.model.ServiceStatus
import moe.shizuku.manager.management.AppsViewModel
import moe.shizuku.manager.ui.MessageDialog
import moe.shizuku.manager.ui.plainText
import moe.shizuku.manager.utils.ShizukuStateMachine
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants

/** Text the status card and its dialog show for one service state. */
internal data class ServiceStatusUi(
    val running: Boolean,
    val restricted: Boolean,
    val needsRestart: Boolean,
    val title: String,
    val subtitle: String?,
    val details: String,
    val versionDetails: String,
)

@Composable
internal fun serviceStatusUi(
    status: ServiceStatus,
    serviceState: ShizukuStateMachine.State,
    installed: PorterServiceVersion = PorterServiceVersion(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
    latestApi: Int = Shizuku.getLatestServiceVersion(),
    latestPatch: Int = ShizukuApiConstants.SERVER_PATCH_VERSION,
): ServiceStatusUi {
    val running = serviceState == ShizukuStateMachine.State.RUNNING && status.uid != -1
    val restricted = running && !status.permission
    val needsRestart = running && (
        status.porterVersion?.matches(installed.name, installed.code) != true ||
            status.apiVersion != latestApi ||
            status.patchVersion != latestPatch
        )
    val title = plainText(stringResource(if (running) R.string.home_status_service_is_running else R.string.home_status_service_not_running, stringResource(R.string.app_name)))
    val subtitle = if (running) stringResource(if (status.uid == 0) R.string.porter_status_running_root else R.string.porter_status_running_adb) else null
    val details = if (running) listOfNotNull(
        if (needsRestart) stringResource(R.string.porter_status_restart_service) else null,
        if (restricted) stringResource(R.string.porter_status_restricted) else null,
    ).joinToString("\n") else ""
    val bidiFormatter = BidiFormatter.getInstance(LocalLayoutDirection.current == LayoutDirection.Rtl)
    val versionDetails = stringResource(R.string.porter_status_versions,
        bidiFormatter.unicodeWrap(installed.name, TextDirectionHeuristics.LTR),
        status.porterVersion?.name?.let { bidiFormatter.unicodeWrap(it, TextDirectionHeuristics.LTR) }
            ?: stringResource(R.string.porter_status_version_unknown),
        status.apiVersion, status.patchVersion)
    return ServiceStatusUi(running, restricted, needsRestart, title, subtitle, details, versionDetails)
}

@Composable
internal fun ServiceStatusCard(ui: ServiceStatusUi, accessPaused: Boolean = false, onDetails: () -> Unit) {
    HomeCard(ui.title, when { ui.restricted -> R.drawable.ic_warning_24; ui.running -> R.drawable.ic_server_ok_24dp; else -> R.drawable.ic_server_error_24dp },
        if (ui.running) onDetails else null,
        when { ui.restricted -> colorResource(R.color.porter_status_warning); ui.running -> colorResource(R.color.porter_status_running); else -> MaterialTheme.colorScheme.onSurfaceVariant },
        subtitle = ui.subtitle, actionLabel = stringResource(R.string.porter_status_details_hint)) {
        if (ui.running) {
            if (ui.details.isNotEmpty()) Text(ui.details, style = MaterialTheme.typography.bodyMedium)
            if (!ui.restricted && !ui.needsRestart) {
                Text(stringResource(if (accessPaused) R.string.porter_status_access_paused else R.string.porter_status_ready), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
internal fun ServiceStatusDialog(ui: ServiceStatusUi, onDismiss: () -> Unit, secondaryUser: Boolean = false, onStop: () -> Unit) {
    val details = listOfNotNull(ui.subtitle, ui.details.takeIf { it.isNotEmpty() }).joinToString("\n")
    val stopMessage = stringResource(if (secondaryUser) R.string.porter_secondary_user_stop_message else R.string.porter_status_stop_message)
    MessageDialog(ui.title, "$details\n\n${ui.versionDetails}\n\n$stopMessage", onDismiss,
        stringResource(R.string.action_stop), confirmColor = MaterialTheme.colorScheme.error, onConfirm = onStop)
}

@Composable
internal fun HomeCardActions(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
internal fun HomeCard(title: String, icon: Int, onClick: (() -> Unit)? = null,
                      tint: Color = MaterialTheme.colorScheme.primary, subtitle: String? = null,
                      containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
                      actionLabel: String? = null,
                      content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth().clip(CardDefaults.shape).then(if (onClick != null) Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = actionLabel, onClick = onClick) else Modifier),
        colors = CardDefaults.cardColors(containerColor = containerColor)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(painterResource(icon), null, Modifier.size(24.dp), tint)
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    subtitle?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (onClick != null) Icon(painterResource(R.drawable.ic_touch_app_24), null,
                    Modifier.size(18.dp).align(Alignment.Top), MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

@Composable
internal fun CompatibilityCard(description: String, onDownload: () -> Unit) {
    HomeCard(stringResource(R.string.compat_setup_title), R.drawable.ic_shizuku,
        onClick = if (BuildConfig.IS_FOSS) onDownload else null, tint = Color.Unspecified,
        containerColor = MaterialTheme.colorScheme.tertiaryContainer, actionLabel = stringResource(R.string.compat_setup_hint)) {
        Text(description, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun compatibilityUsageText(running: Boolean, apps: AppsViewModel.State): String = when {
    !running -> stringResource(R.string.compat_usage_stopped)
    apps.loading || apps.error != null || apps.failedUsers.isNotEmpty() || apps.legacy -> stringResource(R.string.compat_usage_unknown)
    else -> LocalContext.current.resources.getQuantityString(R.plurals.compat_usage_count, apps.companionCount, apps.companionCount)
}

@Composable
internal fun InstalledCompatibilityCard(version: String, usage: String, notice: String? = null, onDetails: () -> Unit) {
    HomeCard(stringResource(R.string.compat_setup_title), R.drawable.ic_shizuku, onDetails, tint = Color.Unspecified,
        subtitle = stringResource(R.string.compat_status_installed),
        containerColor = if (notice != null) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        actionLabel = stringResource(R.string.compat_details_hint)) {
        notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Text(usage, style = MaterialTheme.typography.bodyMedium)
        Text(version, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun compatibilityVersionText(name: String?, code: Long?): String {
    val text = if (name.isNullOrBlank()) stringResource(R.string.compat_version_code, code ?: 0)
        else stringResource(R.string.compat_version, name, code ?: 0)
    return BidiFormatter.getInstance(LocalLayoutDirection.current == LayoutDirection.Rtl)
        .unicodeWrap(text, TextDirectionHeuristics.LTR)
}
