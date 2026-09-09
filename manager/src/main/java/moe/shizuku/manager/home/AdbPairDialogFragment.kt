package moe.shizuku.manager.home

import android.app.Application
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.fragment.app.viewModels
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import moe.shizuku.manager.R
import moe.shizuku.manager.adb.*
import moe.shizuku.manager.ui.ComposeDialogFragment
import moe.shizuku.manager.ui.LocalNetworkPermission
import moe.shizuku.manager.utils.SettingsHelper

class AdbPairDialogFragment : ComposeDialogFragment() {
    private val model: PairingViewModel by viewModels()
    @Composable override fun Content() {
        if (!LocalNetworkPermission { model.startDiscovery() }) {
            TextButton(onClick = { dismissAllowingStateLoss() }) { Text(stringResource(android.R.string.cancel)) }
            return
        }
        val endpoint by model.endpoint.collectAsStateWithLifecycle()
        val busy by model.busy.collectAsStateWithLifecycle()
        val error by model.error.collectAsStateWithLifecycle()
        val success by model.success.collectAsStateWithLifecycle()
        var code by rememberSaveable(endpoint) { mutableStateOf("") }
        var port by rememberSaveable(endpoint) {
            mutableStateOf(if (endpoint.second in 1..65535) endpoint.second.toString() else "")
        }
        LaunchedEffect(error) { if (error != null) code = "" }
        LaunchedEffect(success) { if (success) dismissAllowingStateLoss() }
        val multi = requireActivity().isInMultiWindowMode || (requireActivity().window.decorView.display?.displayId ?: -1) > 0
        Text(stringResource(if (endpoint.second > 0) R.string.dialog_adb_pairing_title else R.string.dialog_adb_pairing_discovery), style = MaterialTheme.typography.headlineSmall)
        if (!multi) {
            Text(stringResource(R.string.adb_pairing_requires_multi_window))
            Text(stringResource(R.string.adb_pairing_requires_multi_window_reason))
        }
        if (endpoint.second <= 0) Text(stringResource(R.string.porter_pairing_dialog_instructions))
        OutlinedTextField(port, { port = it; model.error.value = null }, Modifier.fillMaxWidth(), enabled = !busy,
            label = { Text(stringResource(R.string.dialog_adb_port)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        OutlinedTextField(code, { code = it.take(6); model.error.value = null }, Modifier.fillMaxWidth(), enabled = !busy,
            label = { Text(stringResource(R.string.dialog_adb_pairing_paring_code)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        error?.let { Text(requireContext().pairingFailureMessage(it), color = MaterialTheme.colorScheme.error) }
        if (busy) LinearProgressIndicator()
        TextButton(enabled = !busy, onClick = { SettingsHelper.launchOrHighlightWirelessDebugging(requireContext()) }) { Text(stringResource(R.string.development_settings)) }
        Button(enabled = !busy && port.toIntOrNull()?.let { it in 1..65535 } == true && code.length == 6,
            onClick = { model.pair(port.toInt(), code) }) { Text(stringResource(android.R.string.ok)) }
        TextButton(enabled = !busy, onClick = { code = ""; port = ""; model.restartDiscovery() }) {
            Text(stringResource(R.string.porter_pairing_restart))
        }
        TextButton(onClick = { dismissAllowingStateLoss() }) { Text(stringResource(android.R.string.cancel)) }
    }
}

class PairingViewModel(application: Application) : AndroidViewModel(application) {
    val endpoint = MutableStateFlow("127.0.0.1" to -1)
    val busy = MutableStateFlow(false)
    val success = MutableStateFlow(false)
    val error = MutableStateFlow<Throwable?>(null)
    private val mdns = AdbMdns(application, AdbMdns.TLS_PAIRING) { endpoint.value = it }
    private var discovering = false
    fun startDiscovery() { if (!discovering) { discovering = true; mdns.start() } }
    fun restartDiscovery() {
        mdns.stop()
        discovering = false
        endpoint.value = "127.0.0.1" to -1
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
