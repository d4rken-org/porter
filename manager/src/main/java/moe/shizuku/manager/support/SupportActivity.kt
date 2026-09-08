package moe.shizuku.manager.support

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.shizuku.manager.Helps
import moe.shizuku.manager.R
import moe.shizuku.manager.ui.*
import moe.shizuku.manager.utils.CustomTabsHelper

class SupportActivity : ComposeActivity() {
    private val model: SupportViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        porterContent {
            val state by model.recording.collectAsStateWithLifecycle()
            val sessions by model.sessions.collectAsStateWithLifecycle()
            val busy by model.busy.collectAsStateWithLifecycle()
            PorterScaffold(stringResource(R.string.porter_support_title), onBack = { finish() }) { padding ->
                Column(Modifier.padding(padding).consumeWindowInsets(padding).verticalScroll(rememberScrollState())) {
                    SettingsCategory(stringResource(R.string.porter_support_title))
                    listOf(
                        Triple(R.string.porter_documentation, R.drawable.ic_help_outline_24dp, Helps.WEBSITE),
                        Triple(R.string.porter_issue_tracker, R.drawable.ic_code_24dp, Helps.SOURCE + "/issues"),
                        Triple(R.string.porter_discord, R.drawable.ic_baseline_link_24, "https://discord.gg/5hXXgwKNgm")
                    ).forEach { (title, icon, url) -> SettingsItem(stringResource(title), icon, onClick = { CustomTabsHelper.launchUrlOrCopy(this@SupportActivity, url) }) }
                    SettingsItem(stringResource(R.string.porter_contact), R.drawable.ic_outline_open_in_new_24, stringResource(R.string.porter_contact_summary),
                        onClick = { startActivity(Intent(this@SupportActivity, ContactActivity::class.java)) })
                    SettingsCategory(stringResource(R.string.porter_debug_title))
                    SettingsItem(stringResource(if (state.active) R.string.porter_debug_stop else R.string.porter_debug_start), R.drawable.ic_terminal_24,
                        state.error ?: stringResource(if (state.active) R.string.porter_debug_recording_summary else R.string.porter_debug_start_summary),
                        enabled = !busy, onClick = model::requestRecording)
                    SettingsItem(stringResource(R.string.porter_debug_saved), R.drawable.ic_outline_info_24, stringResource(R.string.porter_debug_count, sessions.count { !it.active }),
                        onClick = { startActivity(Intent(this@SupportActivity, DebugLogsActivity::class.java)) })
                    Text(stringResource(R.string.porter_debug_storage), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            SupportDialogs(model)
        }
    }
    override fun onResume() { super.onResume(); model.refresh() }
}
