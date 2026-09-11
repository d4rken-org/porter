package moe.shizuku.manager.screenshots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import eu.darken.porter.common.DiscoveredApplication
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.R
import moe.shizuku.manager.compatibility.CompatibilityRepository
import moe.shizuku.manager.home.HomeActions
import moe.shizuku.manager.home.HomeScreenContent
import moe.shizuku.manager.home.HomeUiState
import moe.shizuku.manager.home.ServiceStatusUi
import moe.shizuku.manager.home.serviceStatusUi
import moe.shizuku.manager.management.ApplicationManagementList
import moe.shizuku.manager.management.AppsViewModel
import moe.shizuku.manager.model.PorterServiceVersion
import moe.shizuku.manager.model.ServiceStatus
import moe.shizuku.manager.service.ServiceSnapshot
import moe.shizuku.manager.settings.SettingsActions
import moe.shizuku.manager.settings.SettingsScreenContent
import moe.shizuku.manager.settings.SettingsUiState
import moe.shizuku.manager.ui.PorterScaffold
import moe.shizuku.manager.ui.PorterTheme
import moe.shizuku.manager.utils.ShizukuStateMachine
import java.util.Locale

// Play rejects a screenshot whose long side is more than twice its short side, and only 9:16
// portrait / 16:9 landscape are eligible for the promotional surfaces. 1440x2560 at 560 dpi is
// 411x731 dp, exactly 16:9. The dpi also picks the density bucket: porter_mascot exists only under
// drawable-xxxhdpi, which 560 dpi selects.
internal const val DS_PHONE = "spec:width=1440px,height=2560px,dpi=560"

/**
 * Applies the theme with its inputs stated; the preference-reading [PorterTheme] would throw here.
 *
 * Exactly one wrapper per render: nesting two would compose the theme twice.
 */
@Composable
internal fun PorterPreviewWrapper(dark: Boolean, content: @Composable () -> Unit) =
    PorterTheme(dark = dark, content = content)

// --- Mock data ---------------------------------------------------------------------------------

private const val SERVICE_API = 13
private const val SERVICE_PATCH = 6

/**
 * A stand-in launcher icon: a rounded tile with the app's initial, hue derived from the package.
 *
 * PackageManager resolves nothing under layoutlib, so every row would otherwise be iconless.
 */
private fun appIcon(packageName: String, label: String): Bitmap {
    val edge = 96
    val bitmap = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val size = edge.toFloat()
    val tile = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.HSVToColor(floatArrayOf(packageName.hashCode().mod(360).toFloat(), 0.55f, 0.85f))
    }
    canvas.drawRoundRect(RectF(0f, 0f, size, size), size * 0.22f, size * 0.22f, tile)
    val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = size * 0.56f
        typeface = Typeface.DEFAULT_BOLD
    }
    // Locale.ROOT: the render locale must not decide which glyph an initial uppercases to.
    val letter = label.take(1).uppercase(Locale.ROOT).ifEmpty { "?" }
    canvas.drawText(letter, size / 2f, size / 2f - (glyph.descent() + glyph.ascent()) / 2f, glyph)
    return bitmap
}

// lastConnectedAt stays null on every mock app. A non-null value is formatted through
// DateFormat.getDateTimeInstance, which reads the render JVM's default time zone, so the rendered
// pixels would depend on the machine doing the rendering. The "never recorded" string does not.
private fun mockApp(
    packageName: String,
    label: String,
    authorization: Int,
    connectionStatus: Int,
    declaredApis: Int,
) = AppsViewModel.App(
    packageName = packageName,
    uid = 10000 + packageName.hashCode().mod(9000),
    label = label,
    icon = appIcon(packageName, label),
    authorization = authorization,
    connectionStatus = connectionStatus,
    declaredApis = declaredApis,
    requiresRoot = false,
    lastConnectedAt = null,
)

/** 3 authorized of 5 compatible, 2 of them reached through the companion. */
private fun authorizedApps() = listOf(
    mockApp("eu.darken.sdmse", "SD Maid SE", DiscoveredApplication.ALLOWED,
        DiscoveredApplication.DIRECT, DiscoveredApplication.API_PORTER),
    mockApp("eu.darken.butler", "Butler", DiscoveredApplication.ALLOWED,
        DiscoveredApplication.DIRECT, DiscoveredApplication.API_PORTER),
    mockApp("com.example.backup", "Backup Studio", DiscoveredApplication.ALLOWED,
        DiscoveredApplication.COMPANION, DiscoveredApplication.API_SHIZUKU),
    mockApp("com.example.appmanager", "App Manager", DiscoveredApplication.DEFAULT,
        DiscoveredApplication.COMPANION, DiscoveredApplication.API_SHIZUKU),
    mockApp("com.example.logreader", "Log Reader", DiscoveredApplication.DEFAULT,
        DiscoveredApplication.DIRECT, DiscoveredApplication.API_PORTER),
)

/** One row per state the management list can show. */
private fun managedApps() = listOf(
    mockApp("com.example.appmanager", "App Manager", DiscoveredApplication.DEFAULT,
        DiscoveredApplication.DIRECT, DiscoveredApplication.API_PORTER),
    mockApp("eu.darken.butler", "Butler", DiscoveredApplication.ALLOWED,
        DiscoveredApplication.DIRECT, DiscoveredApplication.API_PORTER or DiscoveredApplication.API_SHIZUKU),
    mockApp("com.example.filebrowser", "File Browser", DiscoveredApplication.DENIED,
        DiscoveredApplication.DIRECT, DiscoveredApplication.API_PORTER),
    mockApp("com.example.logreader", "Log Reader", DiscoveredApplication.ALLOWED,
        DiscoveredApplication.NEEDS_COMPANION, DiscoveredApplication.API_SHIZUKU),
    mockApp("eu.darken.sdmse", "SD Maid SE", DiscoveredApplication.ALLOWED,
        DiscoveredApplication.COMPANION, DiscoveredApplication.API_SHIZUKU),
)

/** Granted apps that still wait for the compatibility companion; this is what raises shot 3's card. */
private fun pendingCompanionApps() = listOf(
    mockApp("eu.darken.sdmse", "SD Maid SE", DiscoveredApplication.ALLOWED,
        DiscoveredApplication.DIRECT, DiscoveredApplication.API_PORTER),
    mockApp("com.example.appmanager", "App Manager", DiscoveredApplication.ALLOWED,
        DiscoveredApplication.DIRECT, DiscoveredApplication.API_PORTER),
    mockApp("com.example.logreader", "Log Reader", DiscoveredApplication.ALLOWED,
        DiscoveredApplication.NEEDS_COMPANION, DiscoveredApplication.API_SHIZUKU),
    mockApp("com.example.backup", "Backup Studio", DiscoveredApplication.ALLOWED,
        DiscoveredApplication.NEEDS_COMPANION, DiscoveredApplication.API_SHIZUKU),
)

private fun appsState(apps: List<AppsViewModel.App>) =
    AppsViewModel.State(apps = apps, loading = false, accessEnabled = true)

private val screenshotVersion = PorterServiceVersion("0.1.1-beta1", 101010, "0123456789abcdef:release")

@Composable
private fun runningStatusUi(): ServiceStatusUi = serviceStatusUi(ServiceSnapshot(
    ServiceStatus(uid = 2000, apiVersion = SERVICE_API, patchVersion = SERVICE_PATCH, permission = true, porterVersion = screenshotVersion),
    ShizukuStateMachine.State.RUNNING, installed = screenshotVersion,
))

private val noHomeActions = HomeActions({}, {}, {}, {}, {}, {}, {}, {}, {})

private val noSettingsActions = SettingsActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})

private fun homeState(
    statusUi: ServiceStatusUi,
    apps: AppsViewModel.State,
    compat: CompatibilityRepository.State,
    running: Boolean,
) = HomeUiState(
    statusUi = statusUi,
    appsState = apps,
    compatState = compat,
    buildBadge = null,
    showBatteryCard = false,
    canStart = !running, wirelessAdbAvailable = true, tlsSupported = true,
)

/** Row values come from the real arrays, so a localized render shows the localized choice. */
@Composable
private fun settingsState() = SettingsUiState(
    startOnBoot = true,
    startOnBootEnabled = true,
    watchdog = true,
    autoUpdateService = false,
    autoUpdateServiceEnabled = true,
    showPairingMethod = true,
    pairingMethodLabel = stringArrayResource(R.array.porter_pairing_methods)[0],
    showTcpPort = false,
    tcpPortLabel = stringResource(R.string.settings_tcp_port_default),
    tcpPortNeedsRestart = false,
    themeModeLabel = stringArrayResource(R.array.night_mode)[2],
    themeStyleLabel = stringArrayResource(R.array.porter_theme_styles)[0],
    themeColorLabel = stringArrayResource(R.array.porter_theme_colors)[0],
    themeColorEnabled = true,
    versionName = BuildConfig.VERSION_NAME,
)

// --- Shots -------------------------------------------------------------------------------------

@Composable
internal fun HomeRunningContent() = PorterPreviewWrapper(dark = false) {
    val apps = remember { appsState(authorizedApps()) }
    val compat = remember {
        CompatibilityRepository.State(
            status = CompatibilityRepository.Status.INSTALLED,
            isCompanion = true,
            installedVersionName = "1.2.0",
            installedVersionCode = 1200000L,
        )
    }
    HomeScreenContent(homeState(runningStatusUi(), apps, compat, running = true), noHomeActions)
}

@Composable
internal fun AppsContent() = PorterPreviewWrapper(dark = false) { AppsBody() }

@Composable
internal fun AppsDarkContent() = PorterPreviewWrapper(dark = true) { AppsBody() }

@Composable
private fun AppsBody() {
    val state = remember { appsState(managedApps()) }
    PorterScaffold(stringResource(R.string.home_app_management_title), onBack = {}) { padding ->
        ApplicationManagementList(state, {}, { _, _ -> },
            Modifier.padding(padding).consumeWindowInsets(padding))
    }
}

@Composable
internal fun HomeCompatibilityContent() = PorterPreviewWrapper(dark = false) {
    val apps = remember { appsState(pendingCompanionApps()) }
    HomeScreenContent(
        homeState(runningStatusUi(), apps, CompatibilityRepository.State(), running = true),
        noHomeActions,
    )
}

@Composable
internal fun HomeSetupContent() = PorterPreviewWrapper(dark = false) {
    HomeScreenContent(homeState(serviceStatusUi(ServiceSnapshot(installed = screenshotVersion)),
        AppsViewModel.State(), CompatibilityRepository.State(), running = false), noHomeActions)
}

@Composable
internal fun SettingsContent() = PorterPreviewWrapper(dark = true) {
    SettingsScreenContent(settingsState(), noSettingsActions)
}

// --- IDE previews ------------------------------------------------------------------------------

@Preview(device = DS_PHONE)
@Composable
private fun HomeRunningPreview() = HomeRunningContent()

@Preview(device = DS_PHONE)
@Composable
private fun AppsPreview() = AppsContent()

@Preview(device = DS_PHONE)
@Composable
private fun HomeCompatibilityPreview() = HomeCompatibilityContent()

@Preview(device = DS_PHONE)
@Composable
private fun HomeSetupPreview() = HomeSetupContent()

@Preview(device = DS_PHONE)
@Composable
private fun SettingsPreview() = SettingsContent()

@Preview(device = DS_PHONE)
@Composable
private fun AppsDarkPreview() = AppsDarkContent()
