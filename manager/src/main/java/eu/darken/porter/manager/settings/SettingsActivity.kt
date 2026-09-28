package eu.darken.porter.manager.settings

import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.darken.porter.manager.BuildConfig
import eu.darken.porter.manager.Helps
import eu.darken.porter.manager.ui.*
import eu.darken.porter.manager.updater.UpdateRepository
import eu.darken.porter.manager.utils.CustomTabsHelper

class SettingsActivity : ComposeActivity() {
    private val updates by lazy { UpdateRepository.get(this) }

    /**
     * Bumped on every resume. What the Startup entry reports can be changed from outside this
     * screen, so the read is keyed on this as well as on the preferences.
     */
    private var resumed by mutableIntStateOf(0)

    override fun onResume() {
        super.onResume()
        resumed++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        porterContent { SettingsScreen() }
    }

    @Composable
    private fun SettingsScreen() {
        val revision = preferencesRevision()
        val updateState by updates.state.collectAsStateWithLifecycle()
        val startupNeedsAttention = remember(revision, resumed) { StartupAttention.needsAttention(this@SettingsActivity) }
        var dialog by rememberSaveable { mutableStateOf<String?>(null) }
        SettingsScreenContent(
            SettingsUiState(
                versionName = BuildConfig.VERSION_NAME,
                updateCheckSupported = updateState.supported,
                startupNeedsAttention = startupNeedsAttention,
            ),
            SettingsActions(
                onBack = { finish() },
                onGeneral = { startActivity(Intent(this@SettingsActivity, GeneralSettingsActivity::class.java)) },
                onStartup = { startActivity(Intent(this@SettingsActivity, StartupSettingsActivity::class.java)) },
                onCompatibility = { startActivity(Intent(this@SettingsActivity, eu.darken.porter.manager.compatibility.CompatibilityActivity::class.java)) },
                onTerminal = { startActivity(Intent(this@SettingsActivity, eu.darken.porter.manager.shell.ShellTutorialActivity::class.java)) },
                onAutomation = { dialog = "automation" },
                onDeveloperGuide = { CustomTabsHelper.launchUrlOrCopy(this@SettingsActivity, Helps.HOME.get()) },
                onSupport = { startActivity(Intent(this@SettingsActivity, eu.darken.porter.manager.support.SupportActivity::class.java)) },
                onAcknowledgements = { startActivity(Intent(this@SettingsActivity, AcknowledgementsActivity::class.java)) },
                onVersion = { CustomTabsHelper.launchUrlOrCopy(this@SettingsActivity, Helps.DOWNLOAD.get()) },
            ),
        )
        when (dialog) {
            "automation" -> AutomationSheet { dialog = null }
        }
    }
}
