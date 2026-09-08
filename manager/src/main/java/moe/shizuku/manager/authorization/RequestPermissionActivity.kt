package moe.shizuku.manager.authorization

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.TextUtils
import androidx.activity.compose.BackHandler
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import moe.shizuku.manager.Helps
import moe.shizuku.manager.R
import moe.shizuku.manager.ui.*
import moe.shizuku.manager.utils.Logger.LOGGER
import moe.shizuku.manager.utils.ShizukuStateMachine
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED
import rikka.shizuku.ShizukuApiConstants.REQUEST_PERMISSION_REPLY_IS_ONETIME

class RequestPermissionActivity : ComposeActivity() {
    override val protectTouches = true
    override val rejectPartialTouches = true
    override val edgeToEdge = false
    private val model: PermissionViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false)
        val uid = intent.getIntExtra("uid", -1)
        val pid = intent.getIntExtra("pid", -1)
        val code = intent.getIntExtra("requestCode", -1)
        val ai = intent.getParcelableExtra<ApplicationInfo>("applicationInfo")
        if (uid == -1 || pid == -1 || ai == null) { finish(); return }
        val label = runCatching { ai.loadLabel(packageManager).toString() }.getOrDefault(ai.packageName)
        model.initialize(uid, pid, code)
        porterContent {
            val stage by model.stage.collectAsStateWithLifecycle()
            BackHandler(enabled = stage == "waiting" || stage == "ready") {}
            LaunchedEffect(stage) { if (stage == "finished") finish() }
            PermissionDialogContent(stage, label, onAllow = { model.reply(true) }, onDeny = { model.reply(false) }, onClose = { finish() })
        }
    }
}

@Composable
internal fun PermissionDialogContent(stage: String, label: String, onAllow: () -> Unit, onDeny: () -> Unit, onClose: () -> Unit) {
    DialogSurface {
        when (stage) {
            "ready" -> {
                HtmlText(stringResource(R.string.permission_warning_template, TextUtils.htmlEncode(label), stringResource(R.string.permission_group_description)))
                Button(onClick = onAllow, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.grant_dialog_button_allow_always)) }
                OutlinedButton(onClick = onDeny, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.grant_dialog_button_deny)) }
            }
            "limited" -> {
                Text(stringResource(R.string.app_management_dialog_adb_is_limited_title), style = MaterialTheme.typography.headlineSmall)
                HtmlText(stringResource(R.string.app_management_dialog_adb_is_limited_message, Helps.ADB_PERMISSION.get()))
                TextButton(onClick = onClose) { Text(stringResource(android.R.string.ok)) }
            }
            else -> CircularProgressIndicator()
        }
    }
}

/** The service calls the permission flow depends on; the policy itself stays in [PermissionViewModel]. */
internal interface PermissionGateway {
    fun serviceStates(): Flow<ShizukuStateMachine.State>
    suspend fun canGrantPermissions(): Boolean
    fun dispatch(uid: Int, pid: Int, code: Int, data: Bundle)
}

internal object ShizukuPermissionGateway : PermissionGateway {
    override fun serviceStates() = ShizukuStateMachine.asFlow()
    override suspend fun canGrantPermissions() = withContext(Dispatchers.IO) {
        Shizuku.checkRemotePermission("android.permission.GRANT_RUNTIME_PERMISSIONS") == PackageManager.PERMISSION_GRANTED
    }
    override fun dispatch(uid: Int, pid: Int, code: Int, data: Bundle) = Shizuku.dispatchPermissionConfirmationResult(uid, pid, code, data)
}

class PermissionViewModel internal constructor(private val savedState: SavedStateHandle, private val gateway: PermissionGateway) : ViewModel() {
    constructor(savedState: SavedStateHandle) : this(savedState, gatewayOverride ?: ShizukuPermissionGateway)
    val stage = savedState.getStateFlow("stage", "waiting")
    private val gate = PermissionReplyGate(savedState["replied"] ?: false)
    private var initialized = false
    private var uid = -1
    private var pid = -1
    private var code = -1
    fun initialize(uid: Int, pid: Int, code: Int) {
        if (initialized) return
        initialized = true
        this.uid = uid; this.pid = pid; this.code = code
        if (gate.replied) { if (stage.value != "limited") savedState["stage"] = "finished"; return }
        viewModelScope.launch {
            try {
                withTimeout(SERVICE_TIMEOUT) { gateway.serviceStates().first { it == ShizukuStateMachine.State.RUNNING } }
                val grantable = gateway.canGrantPermissions()
                // A restored prompt is answerable before this check completes; a decision must not be undone by it.
                if (grantable) { if (!gate.replied) savedState["stage"] = "ready" }
                else reply(false, limited = true)
            } catch (e: TimeoutCancellationException) {
                LOGGER.e(e, "Binder not received in 5s")
                savedState["stage"] = "finished"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { LOGGER.e(e, "Permission request failed"); savedState["stage"] = "finished" }
        }
    }
    fun reply(allowed: Boolean, limited: Boolean = false) {
        gate.reply {
            savedState["replied"] = true
            savedState["stage"] = if (limited) "limited" else "finished"
            val data = Bundle().apply {
                putBoolean(REQUEST_PERMISSION_REPLY_ALLOWED, allowed)
                putBoolean(REQUEST_PERMISSION_REPLY_IS_ONETIME, !allowed)
            }
            try { gateway.dispatch(uid, pid, code, data) }
            catch (e: Exception) { LOGGER.e(e, "dispatchPermissionConfirmationResult") }
        }
    }
    companion object {
        const val SERVICE_TIMEOUT = 5000L
        /** Test seam for the activity's default view-model factory; production leaves it null. */
        internal var gatewayOverride: PermissionGateway? = null
    }
}
