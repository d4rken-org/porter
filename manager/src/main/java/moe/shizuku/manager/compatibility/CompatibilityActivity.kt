package moe.shizuku.manager.compatibility

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.Helps
import moe.shizuku.manager.R
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.management.AppsViewModel
import moe.shizuku.manager.home.HomeCardActions
import moe.shizuku.manager.home.HomeCard
import moe.shizuku.manager.home.compatibilityVersionText
import moe.shizuku.manager.home.compatibilityUsageText
import moe.shizuku.manager.ui.ComposeActivity
import moe.shizuku.manager.ui.PorterScaffold
import moe.shizuku.manager.utils.CustomTabsHelper

class CompatibilityActivity : ComposeActivity() {
    override val protectTouches = true
    override val rejectPartialTouches = true
    private val appsModel: AppsViewModel by viewModels()
    private val repository by lazy { CompatibilityRepository.get(this) }
    private var manualError by mutableStateOf<String?>(null)
    private val installerPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()) manualInstall()
    }
    private val installer = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { repository.clearStagedApk(); repository.afterAndroidInstaller(); appsModel.load() }
    private val uninstaller = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_FIRST_USER) manualError = getString(R.string.compat_uninstall_unavailable)
        repository.afterAndroidUninstaller()
        appsModel.load()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        porterContent { Screen() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) { repository.refresh(); delay(2000) }
            }
        }
    }

    override fun onResume() { super.onResume(); repository.refresh(); appsModel.load() }

    private fun manualInstall() {
        if (!BuildConfig.IS_FOSS) return
        manualError = null
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            try { installerPermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
            catch (e: android.content.ActivityNotFoundException) { manualError = getString(R.string.compat_installer_settings_unavailable) }
            return
        }
        lifecycleScope.launch {
            try {
                val apk = withContext(Dispatchers.IO) { repository.bundledApk() }
                val uri = FileProvider.getUriForFile(this@CompatibilityActivity, "$packageName.fileprovider", apk)
                installer.launch(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (e: Exception) { manualError = e.message ?: e.javaClass.simpleName }
        }
    }

    private fun manualUninstall() {
        manualError = null
        try {
            val target = repository.verifiedUninstallPackage()
            uninstaller.launch(Intent(Intent.ACTION_DELETE, Uri.parse("package:$target"))
                .putExtra(Intent.EXTRA_RETURN_RESULT, true))
        } catch (e: Exception) { manualError = e.message ?: getString(R.string.compat_uninstall_unavailable) }
    }

    @Composable private fun Screen() {
        val state by repository.state.collectAsStateWithLifecycle()
        val apps by appsModel.state.collectAsStateWithLifecycle()
        val busy = state.phase != null
        LaunchedEffect(state.status, state.installedVersionCode, state.running) {
            if (state.running) appsModel.load()
        }
        LaunchedEffect(state.manualUninstallRequired, busy) {
            if (state.manualUninstallRequired && !busy) {
                repository.consumeManualUninstall()
                manualUninstall()
            }
        }
        var confirmUninstall by rememberSaveable { mutableStateOf(false) }
        val newer = (state.installedVersionCode ?: 0) > BuildConfig.VERSION_CODE
        PorterScaffold(stringResource(R.string.compat_setup_title), onBack = { finish() }) { padding ->
            Column(Modifier.padding(padding).consumeWindowInsets(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.status != CompatibilityRepository.Status.INSTALLED) CompatibilityExplanationCard()
                HomeCard(stringResource(when (state.status) {
                    CompatibilityRepository.Status.MISSING -> R.string.compat_status_missing
                    CompatibilityRepository.Status.INSTALLED -> R.string.compat_status_installed
                    CompatibilityRepository.Status.UPDATE -> R.string.compat_status_update
                    CompatibilityRepository.Status.CONFLICT -> R.string.compat_status_conflict
                    CompatibilityRepository.Status.INVALID -> R.string.compat_status_invalid
                }), if (state.status == CompatibilityRepository.Status.INSTALLED) R.drawable.ic_baseline_link_24 else R.drawable.ic_outline_info_24) {
                    if (state.isCompanion) {
                        OutlinedCard(
                            onClick = {
                                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:${CompatibilityRepository.PACKAGE}")))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(state.installedLabel, style = MaterialTheme.typography.titleSmall)
                                Text(android.text.BidiFormatter.getInstance().unicodeWrap(CompatibilityRepository.PACKAGE, android.text.TextDirectionHeuristics.LTR), style = MaterialTheme.typography.bodySmall)
                                Text(compatibilityVersionText(state.installedVersionName, state.installedVersionCode),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Text(compatibilityUsageText(state.running, apps), style = MaterialTheme.typography.bodyMedium)
                        if (state.running) Text(stringResource(R.string.compat_usage_explanation), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (BuildConfig.IS_FOSS && repository.primaryUser && !state.otherUsers
                        && state.status in setOf(CompatibilityRepository.Status.UPDATE, CompatibilityRepository.Status.INVALID)) {
                        if (newer) Text(stringResource(R.string.compat_newer_installed))
                        InstallationActions(state, newer, if (state.status == CompatibilityRepository.Status.UPDATE)
                            R.string.compat_update else R.string.compat_repair)
                    }
                    if (busy && state.phase != CompatibilityRepository.Phase.PREPARING && state.preview == null) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(when (state.phase) {
                            CompatibilityRepository.Phase.STOPPING -> R.string.compat_progress_stopping
                            CompatibilityRepository.Phase.UNINSTALLING -> R.string.compat_progress_uninstalling
                            CompatibilityRepository.Phase.REMOVING -> R.string.compat_progress_removing
                            CompatibilityRepository.Phase.INSTALLING -> R.string.compat_progress_installing
                            CompatibilityRepository.Phase.IMPORTING -> R.string.compat_progress_importing
                            CompatibilityRepository.Phase.ACTIVATING -> R.string.compat_progress_activating
                            else -> R.string.compat_progress_checking
                        }))
                    }
                    state.inspectionError?.let {
                        Text(stringResource(R.string.compat_check_retrying), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    state.error?.let {
                        Text(stringResource(R.string.compat_failed), color = MaterialTheme.colorScheme.error)
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                    manualError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                if (!BuildConfig.IS_FOSS) {
                    HomeCard(stringResource(R.string.compat_help), R.drawable.ic_help_outline_24dp) {
                        Text(stringResource(R.string.compat_other_build))
                        HomeCardActions {
                            TextButton(onClick = { CustomTabsHelper.launchUrlOrCopy(this@CompatibilityActivity, Helps.APPS.get()) }) {
                                Text(stringResource(R.string.compat_help))
                            }
                        }
                    }
                } else if (!repository.primaryUser) {
                    HomeCard(stringResource(R.string.compat_setup_title), R.drawable.ic_outline_info_24) {
                        Text(stringResource(R.string.compat_primary_user))
                    }
                } else {
                    if (state.hasSnapshot) {
                        HomeCard(stringResource(R.string.compat_import_saved), R.drawable.ic_apps_outline_24) {
                            Text(stringResource(R.string.compat_saved_import))
                            HomeCardActions {
                                TextButton(enabled = !busy, onClick = repository::discardImport) { Text(stringResource(R.string.compat_discard_import)) }
                                if (state.status == CompatibilityRepository.Status.INSTALLED && state.running && state.supported && !state.otherUsers) {
                                    Button(enabled = !busy, onClick = repository::install) { Text(stringResource(R.string.compat_import_saved)) }
                                }
                            }
                        }
                    }
                    if (state.otherUsers) {
                        HomeCard(stringResource(R.string.compat_setup_title), R.drawable.ic_outline_info_24) {
                            Text(stringResource(R.string.compat_other_users))
                        }
                    } else if (state.status == CompatibilityRepository.Status.CONFLICT) {
                        HomeCard(stringResource(R.string.compat_review_replacement), R.drawable.ic_warning_24) {
                            Text(stringResource(R.string.compat_conflict_description))
                            if (!state.running) StartPorterButton(busy)
                            else if (!state.supported) Text(stringResource(R.string.compat_restart_porter))
                            else HomeCardActions {
                                Button(enabled = !state.hasSnapshot && (!busy || state.phase == CompatibilityRepository.Phase.PREPARING),
                                    onClick = repository::prepareReplacement) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(stringResource(R.string.compat_replace_action),
                                            Modifier.alpha(if (state.phase == CompatibilityRepository.Phase.PREPARING) 0f else 1f))
                                        if (state.phase == CompatibilityRepository.Phase.PREPARING) {
                                            CircularProgressIndicator(Modifier.size(18.dp), color = LocalContentColor.current, strokeWidth = 2.dp)
                                        }
                                    }
                                }
                            }
                        }
                    } else if (state.status == CompatibilityRepository.Status.MISSING) {
                        HomeCard(stringResource(R.string.compat_direct_title), R.drawable.ic_adb_24dp) {
                            Text(stringResource(R.string.compat_direct_description))
                            InstallationActions(state, newer, R.string.compat_install)
                        }
                    }
                    if (state.isCompanion) {
                        HomeCard(stringResource(R.string.compat_management_title), R.drawable.ic_action_settings_24dp) {
                            if (state.status == CompatibilityRepository.Status.INSTALLED && !state.otherUsers) {
                                Text(stringResource(R.string.compat_reinstall_description))
                                if (newer) Text(stringResource(R.string.compat_newer_installed))
                                if (!state.running) StartPorterButton(busy)
                                else if (!state.supported) Text(stringResource(R.string.compat_restart_porter))
                                else HomeCardActions {
                                    OutlinedButton(enabled = !busy && !newer, onClick = repository::reinstall) {
                                        Text(stringResource(R.string.compat_reinstall))
                                    }
                                }
                            }
                            Text(stringResource(R.string.compat_uninstall_description))
                            HomeCardActions {
                                TextButton(enabled = !busy, onClick = { confirmUninstall = true },
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                                    Text(stringResource(R.string.compat_uninstall))
                                }
                            }
                        }
                    }
                }
                if (state.status == CompatibilityRepository.Status.INSTALLED) CompatibilityExplanationCard()
            }
        }
        if (!confirmUninstall) CompatibilityImportResultDialog(state, repository::dismissResult)
        if (confirmUninstall) {
            AlertDialog(onDismissRequest = { confirmUninstall = false },
                title = { Text(stringResource(R.string.compat_uninstall_title)) },
                text = { Text(stringResource(R.string.compat_uninstall_description)) },
                confirmButton = {
                    TextButton(onClick = { confirmUninstall = false; repository.uninstall() },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Text(stringResource(R.string.compat_uninstall))
                    }
                },
                dismissButton = { TextButton(onClick = { confirmUninstall = false }) { Text(stringResource(android.R.string.cancel)) } })
        }
        state.preview?.let { preview ->
            AlertDialog(onDismissRequest = { if (!busy) repository.dismissPreview() },
                title = { Text(stringResource(R.string.compat_replace_title)) },
                text = {
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.compat_replace_description, state.installedLabel))
                        Text(stringResource(if (state.previewUnavailable) R.string.compat_import_unavailable else if (preview.isEmpty()) R.string.compat_import_empty else R.string.compat_import_description))
                        if (busy) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(stringResource(if (state.phase == CompatibilityRepository.Phase.STOPPING) R.string.compat_progress_stopping else R.string.compat_progress_checking))
                        }
                        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (state.stopRequired) {
                            Text(stringResource(R.string.compat_stop_manually))
                            HomeCardActions {
                                TextButton(onClick = {
                                    val launch = packageManager.getLaunchIntentForPackage(CompatibilityRepository.PACKAGE)
                                    try {
                                        if (launch != null) startActivity(launch)
                                        else manualError = getString(R.string.compat_original_open_failed)
                                    } catch (e: android.content.ActivityNotFoundException) { manualError = getString(R.string.compat_original_open_failed) }
                                }) { Text(stringResource(R.string.compat_open_original)) }
                            }
                        }
                        manualError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                },
                confirmButton = { TextButton(enabled = !busy, onClick = repository::replace) { Text(stringResource(R.string.compat_replace)) } },
                dismissButton = { TextButton(enabled = !busy, onClick = repository::dismissPreview) { Text(stringResource(android.R.string.cancel)) } })
        }
    }

    @Composable private fun InstallationActions(state: CompatibilityRepository.State, newer: Boolean, action: Int) {
        val busy = state.phase != null
        if (!state.running) Text(stringResource(R.string.compat_direct_stopped))
        else if (!state.supported) Text(stringResource(R.string.compat_restart_porter))
        HomeCardActions {
            TextButton(enabled = !busy && !newer, onClick = ::manualInstall) {
                Text(stringResource(R.string.compat_manual))
            }
            if (!state.running) {
                Button(enabled = !busy, onClick = { startActivity(Intent(this@CompatibilityActivity, MainActivity::class.java)) }) {
                    Text(stringResource(R.string.compat_start_porter))
                }
            } else {
                Button(enabled = !busy && !newer && state.supported, onClick = repository::install) {
                    Text(stringResource(action))
                }
            }
        }
    }

    @Composable private fun StartPorterButton(busy: Boolean) {
        HomeCardActions {
            Button(enabled = !busy, onClick = { startActivity(Intent(this@CompatibilityActivity, MainActivity::class.java)) }) {
                Text(stringResource(R.string.compat_start_porter))
            }
        }
    }
}

@Composable
internal fun CompatibilityExplanationCard() {
    HomeCard(stringResource(R.string.compat_about_title), R.drawable.ic_outline_info_24) {
        Text(stringResource(R.string.compat_setup_description), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.compat_about_direct), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun CompatibilityImportResultDialog(state: CompatibilityRepository.State, onDismiss: () -> Unit) {
    if (!state.completed || state.status != CompatibilityRepository.Status.INSTALLED
        || state.phase != null || state.preview != null || state.applied + state.skipped == 0) return
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.compat_import_complete)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.compat_import_applied, state.applied))
                    Text(stringResource(R.string.compat_import_skipped, state.skipped))
                }
                if (state.skipped > 0) Text(stringResource(R.string.compat_import_skipped_explanation),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.compat_dismiss_result)) }
        })
}
