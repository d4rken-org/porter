package moe.shizuku.manager.starter

import android.app.Application
import android.os.Bundle
import android.util.Log
import androidx.activity.viewModels
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import java.net.ConnectException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLProtocolException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import moe.shizuku.manager.AppConstants.EXTRA
import moe.shizuku.manager.R
import moe.shizuku.manager.adb.AdbKeyException
import moe.shizuku.manager.adb.AdbStarter
import moe.shizuku.manager.utils.ShizukuStateMachine


import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.shizuku.manager.ui.*

private class NotRootedException: Exception()

class StarterActivity : ComposeActivity() {
    private val model: StarterViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        porterContent {
            val output by model.output.collectAsStateWithLifecycle()
            var dismissed by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(output.text) {
                if (output.text.trim().endsWith(Starter.serviceStartedMessage)) { delay(3000); finish() }
            }
            PorterScaffold(stringResource(R.string.home_root_button_start), onBack = { finish() }) { padding ->
                SelectionContainer(Modifier.padding(padding).consumeWindowInsets(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
                    Text(output.text.trim(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
            val message = when (output.error) {
                is AdbKeyException -> R.string.adb_error_key_store
                is NotRootedException -> R.string.start_with_root_failed
                is SocketTimeoutException, is ConnectException -> R.string.cannot_connect_port
                is SSLProtocolException -> R.string.adb_pair_required
                else -> null
            }
            if (message != null && !dismissed) MessageDialog(stringResource(R.string.porter_support_error), stringResource(message), { dismissed = true })
        }
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) model.start(intent.getBooleanExtra(EXTRA_IS_ROOT, false), intent.getIntExtra(EXTRA_PORT, 0))
    }
    companion object {
        const val EXTRA_IS_ROOT = "$EXTRA.IS_ROOT"
        const val EXTRA_PORT = "$EXTRA.PORT"
    }
}

class StarterViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = getApplication<Application>().applicationContext

    private val sb = StringBuilder()
    data class Output(val text: String = "", val error: Throwable? = null)
    private val _output = MutableStateFlow(Output())
    val output = _output.asStateFlow()

    private val handler = CoroutineExceptionHandler { _, throwable ->
        ShizukuStateMachine.update()
        log(error = throwable)
    }

    private var started = false

    fun start(root: Boolean, port: Int) {
        if (started) return
        started = true

        viewModelScope.launch(handler) {
            if (root) startRoot()
            else AdbStarter.startAdb(appContext, port, { log(it) })
            Starter.waitForBinder({ log(it) })
        }
    }

    @Synchronized
    private fun log(line: String? = null, error: Throwable? = null) {
        line?.let { sb.appendLine(it) }
        error?.let { sb.appendLine().appendLine(Log.getStackTraceString(it)) }

        _output.value = Output(sb.toString(), error)
    }

    private suspend fun startRoot() {
        log("Starting with root...\n")

        return withContext(Dispatchers.IO) {
            if (!Shell.getShell().isRoot) {
                // Try again just in case
                Shell.getCachedShell()?.close()

                if (!Shell.getShell().isRoot) {
                    Shell.getCachedShell()?.close()
                    throw NotRootedException()
                }
            }

            ShizukuStateMachine.set(ShizukuStateMachine.State.STARTING)
            suspendCancellableCoroutine { cont ->
                Shell.cmd(Starter.internalCommand)
                    .to(object : CallbackList<String?>() {
                        override fun onAddElement(s: String?) { s?.let { log(it) } }
                    })
                    .submit {
                        if (it.isSuccess) {
                            cont.resume(Unit)
                        } else {
                            cont.resumeWithException(Exception("Failed to start with root"))
                        }
                    }
            }
        }
    }
    
}
