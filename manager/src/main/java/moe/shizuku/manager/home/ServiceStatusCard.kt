package moe.shizuku.manager.home

import android.text.BidiFormatter
import android.text.TextDirectionHeuristics
import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.KeyboardArrowRight
import androidx.compose.material.icons.twotone.CheckCircle
import androidx.compose.material.icons.twotone.Error
import androidx.compose.material.icons.twotone.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import moe.shizuku.manager.R
import moe.shizuku.manager.management.AppsViewModel
import moe.shizuku.manager.ui.plainText
import moe.shizuku.manager.utils.ShizukuStateMachine

internal data class ServiceStatusUi(
    val running: Boolean,
    val restricted: Boolean,
    val updateAvailable: Boolean,
    val title: String,
    val subtitle: String?,
    val details: String,
    val busy: Boolean = false,
)

internal val ServiceStatusUi.statusIcon: ImageVector
    get() = when { restricted -> Icons.TwoTone.Warning; running -> Icons.TwoTone.CheckCircle; else -> Icons.TwoTone.Error }

@Composable
internal fun ServiceStatusUi.statusTint(): Color = when {
    restricted -> colorResource(R.color.porter_status_warning)
    running -> colorResource(R.color.porter_status_running)
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
internal fun serviceStatusUi(snapshot: moe.shizuku.manager.service.ServiceSnapshot): ServiceStatusUi {
    val title = when {
        snapshot.updating -> stringResource(R.string.porter_service_updating)
        snapshot.serviceState == ShizukuStateMachine.State.STARTING -> stringResource(R.string.porter_service_starting)
        snapshot.serviceState == ShizukuStateMachine.State.STOPPING -> stringResource(R.string.porter_service_stopping)
        snapshot.serviceState == ShizukuStateMachine.State.RUNNING && !snapshot.running -> stringResource(R.string.porter_service_checking)
        else -> plainText(stringResource(if (snapshot.running) R.string.home_status_service_is_running else R.string.home_status_service_not_running, stringResource(R.string.app_name)))
    }
    val subtitle = if (snapshot.running) stringResource(if (snapshot.status.uid == 0) R.string.porter_status_running_root else R.string.porter_status_running_adb) else null
    val details = listOfNotNull(
        when {
            snapshot.failed && !snapshot.busy -> stringResource(R.string.porter_service_update_failed)
            snapshot.updateAvailable && !snapshot.busy -> stringResource(R.string.porter_service_update_available)
            else -> null
        },
        if (snapshot.restricted) stringResource(R.string.porter_status_restricted) else null,
    ).joinToString("\n")
    return ServiceStatusUi(snapshot.running, snapshot.restricted, snapshot.updateAvailable, title, subtitle, details, snapshot.busy)
}

@DrawableRes internal fun homeTitleIcon(running: Boolean, restricted: Boolean): Int = when {
    !running -> R.drawable.porter_mascot
    restricted -> R.drawable.porter_mascot_unhappy
    else -> R.drawable.porter_mascot_happy
}

@Composable
internal fun ServiceStatusCard(ui: ServiceStatusUi, onDetails: () -> Unit) {
    HomeCard(ui.title, ui.statusIcon, onDetails, ui.statusTint(),
        subtitle = ui.subtitle, actionLabel = stringResource(R.string.porter_view_service)) {
        if (ui.details.isNotEmpty()) Text(ui.details, style = MaterialTheme.typography.bodyMedium)
        if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
internal fun HomeCardActions(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
internal fun HomeCard(title: String, icon: ImageVector, onClick: (() -> Unit)? = null,
                      tint: Color = MaterialTheme.colorScheme.primary, subtitle: String? = null,
                      containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
                      actionLabel: String? = null,
                      content: @Composable ColumnScope.() -> Unit) {
    HomeCard(title, rememberVectorPainter(icon), onClick, tint, subtitle, containerColor, actionLabel, content)
}

@Composable
internal fun HomeCard(title: String, icon: Painter, onClick: (() -> Unit)? = null,
                      tint: Color = MaterialTheme.colorScheme.primary, subtitle: String? = null,
                      containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
                      actionLabel: String? = null,
                      content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth().clip(CardDefaults.shape).then(if (onClick != null) Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = actionLabel, onClick = onClick) else Modifier),
        colors = CardDefaults.cardColors(containerColor = containerColor)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(icon, null, Modifier.size(24.dp), tint)
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    subtitle?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (onClick != null) Icon(Icons.AutoMirrored.TwoTone.KeyboardArrowRight, null,
                    Modifier.size(18.dp).align(Alignment.Top), MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

@Composable
internal fun CompatibilityCard(description: String, affectedApps: String? = null, onDownload: () -> Unit) {
    HomeCard(stringResource(R.string.compat_setup_title), painterResource(R.drawable.ic_shizuku),
        onClick = onDownload, tint = Color.Unspecified,
        containerColor = MaterialTheme.colorScheme.tertiaryContainer, actionLabel = stringResource(R.string.compat_setup_hint)) {
        Text(description, style = MaterialTheme.typography.bodyMedium)
        affectedApps?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
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
    HomeCard(stringResource(R.string.compat_setup_title), painterResource(R.drawable.ic_shizuku), onDetails, tint = Color.Unspecified,
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
