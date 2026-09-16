package eu.darken.porter.manager.home

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import eu.darken.porter.manager.R
import eu.darken.porter.manager.adb.AdbMdns
import eu.darken.porter.manager.starter.StarterActivity
import eu.darken.porter.manager.ui.ComposeDialogFragment
import eu.darken.porter.manager.ui.LocalNetworkPermission
import eu.darken.porter.manager.utils.SettingsPage

class AdbDialogFragment : ComposeDialogFragment() {
    private val model: DiscoveryViewModel by viewModels()
    /** Written while [Content] composes and read by [Actions], which composes after it. */
    private var networkReady by mutableStateOf(false)

    @Composable override fun Content() {
        networkReady = LocalNetworkPermission { model.startDiscovery() }
        if (!networkReady) return
        val port by model.port.collectAsStateWithLifecycle()
        LaunchedEffect(port) {
            if (port in 1..65535 && model.consume()) {
                startActivity(Intent(requireContext(), StarterActivity::class.java).putExtra(StarterActivity.EXTRA_PORT, port))
                dismissAllowingStateLoss()
            }
        }
        Text(stringResource(R.string.dialog_adb_discovery), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.dialog_adb_discovery_message))
        Text(stringResource(R.string.dialog_adb_discovery_message_toggle_wireless_debugging))
        LinearProgressIndicator()
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable override fun Actions() {
        FlowRow(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (networkReady) TextButton(onClick = { SettingsPage.Developer.HighlightWirelessDebugging.launch(requireContext()) }) { Text(stringResource(R.string.development_settings)) }
            TextButton(onClick = { dismissAllowingStateLoss() }) { Text(stringResource(android.R.string.cancel)) }
        }
    }
}

class DiscoveryViewModel(application: Application) : AndroidViewModel(application) {
    val port = MutableStateFlow(-1)
    private var consumed = false
    private val mdns = AdbMdns(application, AdbMdns.TLS_CONNECT) { port.value = it.second }
    private var discovering = false
    fun startDiscovery() {
        if (discovering) return
        discovering = true
        val application = getApplication<Application>()
        if (application.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) Settings.Global.putInt(application.contentResolver, "adb_wifi_enabled", 1)
        mdns.start()
    }
    fun consume(): Boolean { if (consumed) return false; consumed = true; return true }
    override fun onCleared() { mdns.stop() }
}
