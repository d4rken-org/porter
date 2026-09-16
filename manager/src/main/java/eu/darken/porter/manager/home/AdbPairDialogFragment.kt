package eu.darken.porter.manager.home

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import eu.darken.porter.manager.R
import eu.darken.porter.manager.adb.*
import eu.darken.porter.manager.ui.ComposeDialogFragment
import eu.darken.porter.manager.ui.LocalNetworkPermission
import eu.darken.porter.manager.utils.SettingsHelper

class AdbPairDialogFragment : ComposeDialogFragment() {
    private val model: PairingViewModel by viewModels()
    /** Written while [Content] composes and read by [Actions], which composes after it. */
    private var networkReady by mutableStateOf(false)

    @Composable override fun Content() {
        networkReady = LocalNetworkPermission { model.startDiscovery() }
        if (!networkReady) return
        val endpoint by model.endpoint.collectAsStateWithLifecycle()
        val busy by model.busy.collectAsStateWithLifecycle()
        val error by model.error.collectAsStateWithLifecycle()
        val success by model.success.collectAsStateWithLifecycle()
        val code by model.code.collectAsStateWithLifecycle()
        val port by model.port.collectAsStateWithLifecycle()
        LaunchedEffect(error) { if (error != null) model.code.value = "" }
        LaunchedEffect(success) { if (success) dismissAllowingStateLoss() }
        val multi = requireActivity().isInMultiWindowMode || (requireActivity().window.decorView.display?.displayId ?: -1) > 0
        Text(stringResource(if (endpoint.second > 0) R.string.dialog_adb_pairing_title else R.string.dialog_adb_pairing_discovery), style = MaterialTheme.typography.headlineSmall)
        if (!multi) {
            Text(stringResource(R.string.adb_pairing_requires_multi_window))
            Text(stringResource(R.string.adb_pairing_requires_multi_window_reason))
        }
        if (endpoint.second <= 0) Text(stringResource(R.string.porter_pairing_dialog_instructions))
        OutlinedTextField(port, { model.port.value = it; model.error.value = null }, Modifier.fillMaxWidth(), enabled = !busy,
            label = { Text(stringResource(R.string.dialog_adb_port)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        OutlinedTextField(code, { model.code.value = it.take(6); model.error.value = null }, Modifier.fillMaxWidth(), enabled = !busy,
            label = { Text(stringResource(R.string.dialog_adb_pairing_paring_code)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        error?.let { Text(requireContext().pairingFailureMessage(it), color = MaterialTheme.colorScheme.error) }
        if (busy) LinearProgressIndicator()
        TextButton(enabled = !busy, onClick = { model.restartDiscovery() }) {
            Text(stringResource(R.string.porter_pairing_restart))
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable override fun Actions() {
        val busy by model.busy.collectAsStateWithLifecycle()
        val code by model.code.collectAsStateWithLifecycle()
        val port by model.port.collectAsStateWithLifecycle()
        FlowRow(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (networkReady) {
                TextButton(enabled = !busy, onClick = { SettingsHelper.launchOrHighlightWirelessDebugging(requireContext()) }) { Text(stringResource(R.string.development_settings)) }
                Button(enabled = !busy && port.toIntOrNull()?.let { it in 1..65535 } == true && code.length == 6,
                    onClick = { model.pair(port.toInt(), code) }) { Text(stringResource(android.R.string.ok)) }
            }
            TextButton(onClick = { dismissAllowingStateLoss() }) { Text(stringResource(android.R.string.cancel)) }
        }
    }
}

class PairingViewModel(application: Application, savedStateHandle: SavedStateHandle) : AndroidViewModel(application) {
    val endpoint = MutableStateFlow("127.0.0.1" to -1)
    val busy = MutableStateFlow(false)
    val success = MutableStateFlow(false)
    val error = MutableStateFlow<Throwable?>(null)
    /** Handle-backed so what the user typed outlives process death; every write lands in saved state. */
    val code = savedStateHandle.getMutableStateFlow("pairing_code", "")
    val port = savedStateHandle.getMutableStateFlow("pairing_port", "")
    private val mdns = AdbMdns(application, AdbMdns.TLS_PAIRING) { onDiscovered(it) }
    /** The mDNS callback, reachable from tests so a discovery can be delivered without a service. */
    internal fun onDiscovered(discovered: Pair<String, Int>) {
        if (endpoint.value == discovered) return
        // A move away from an endpoint that was already resolved invalidates both the code and the
        // port that belonged to it. The first resolve of a session, including the one after a
        // restore, has nothing to invalidate and only fills in what is still empty.
        val replacesAResolvedEndpoint = endpoint.value.second in 1..65535
        endpoint.value = discovered
        if (replacesAResolvedEndpoint) {
            code.value = ""
            port.value = if (discovered.second in 1..65535) discovered.second.toString() else ""
        } else if (port.value.isBlank() && discovered.second in 1..65535) {
            port.value = discovered.second.toString()
        }
    }
    private var discovering = false
    fun startDiscovery() { if (!discovering) { discovering = true; mdns.start() } }
    fun restartDiscovery() {
        mdns.stop()
        discovering = false
        endpoint.value = "127.0.0.1" to -1
        code.value = ""
        port.value = ""
        error.value = null
        startDiscovery()
    }
    fun pair(port: Int, password: String) {
        if (busy.value) return
        val host = endpoint.value.first
        busy.value = true
        error.value = null
        viewModelScope.launch {
            try {
                pairAdb(host.ifEmpty { "127.0.0.1" }, port, password)
                success.value = true
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error.value = e }
            finally { busy.value = false }
        }
    }
    override fun onCleared() { mdns.stop() }
}
