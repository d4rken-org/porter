package moe.shizuku.manager.home

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.viewModels
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import moe.shizuku.manager.R
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.starter.StarterActivity
import moe.shizuku.manager.ui.ComposeDialogFragment
import moe.shizuku.manager.ui.LocalNetworkPermission
import moe.shizuku.manager.utils.SettingsPage

class AdbDialogFragment : ComposeDialogFragment() {
    private val model: DiscoveryViewModel by viewModels()
    @Composable override fun Content() {
        if (!LocalNetworkPermission { model.startDiscovery() }) {
            TextButton(onClick = { dismissAllowingStateLoss() }) { Text(stringResource(android.R.string.cancel)) }
            return
        }
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
        TextButton(onClick = { SettingsPage.Developer.HighlightWirelessDebugging.launch(requireContext()) }) { Text(stringResource(R.string.development_settings)) }
        TextButton(onClick = { dismissAllowingStateLoss() }) { Text(stringResource(android.R.string.cancel)) }
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
