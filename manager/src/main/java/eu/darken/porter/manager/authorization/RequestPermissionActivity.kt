package eu.darken.porter.manager.authorization

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.text.TextUtils
import androidx.activity.compose.BackHandler
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Android
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import eu.darken.porter.manager.Helps
import eu.darken.porter.manager.R
import eu.darken.porter.manager.ServerBinder
import eu.darken.porter.manager.ui.*
import eu.darken.porter.manager.utils.LOGGER
import eu.darken.porter.manager.utils.PorterStateMachine
import eu.darken.porter.manager.utils.PorterSystemApis
import eu.darken.porter.manager.utils.UserHandleCompat
import eu.darken.porter.server.IPorterService
import android.content.pm.PackageManager

class RequestPermissionActivity : ComposeActivity() {
    override val protectTouches = true
    override val rejectPartialTouches = true
    override val edgeToEdge = false
    private val model: PermissionViewModel by viewModels()

    /** The request this prompt is asking about; [onNewIntent] replaces it. */
    private var request by mutableStateOf<PermissionRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false)
        val first = requested(intent)
        if (first == null) { finish(); return }
        request = first
        model.initialize(first.uid, first.pid, first.code)
        porterContent {
            val asked = request ?: return@porterContent
            val ai = asked.info
            val stage by model.stage.collectAsStateWithLifecycle()
            val identity by produceState(RequestingApp(asked.label, ai.packageName, null, null), asked) {
                val icon = withContext(Dispatchers.IO) { runCatching { ai.loadIcon(packageManager).toBitmap(96, 96).asImageBitmap() }.getOrNull() }
                value = RequestingApp(asked.label, ai.packageName, icon, null)
                val userId = UserHandleCompat.getUserId(asked.uid)
                if (userId == UserHandleCompat.myUserId()) return@produceState
                // The user name comes from the service, so the row waits for it. No answer within
                // the bound leaves the row out, which is honest where a placeholder name is not.
                val profile = try {
                    withTimeout(PermissionViewModel.SERVICE_TIMEOUT) {
                        PorterStateMachine.instance.asFlow().first { it == PorterStateMachine.State.RUNNING }
                        withContext(Dispatchers.IO) { runCatching { PorterSystemApis.instance.getUserInfo(userId).let { "${it.name} ($userId)" } }.getOrNull() }
                    }
                } catch (e: TimeoutCancellationException) {
                    LOGGER.e(e, "Binder not received in 5s, requesting user not named")
                    null
                } ?: return@produceState
                value = RequestingApp(asked.label, ai.packageName, icon, profile)
            }
            BackHandler(enabled = stage == "waiting" || stage == "ready") {}
            LaunchedEffect(stage) { if (stage == "finished") finish() }
            PermissionDialogContent(stage, identity, onAllow = { model.reply(true) }, onDeny = { model.reply(false) }, onClose = { finish() })
        }
    }

    /**
     * A request that arrives while this prompt is up, which no new instance is created for.
     *
     * Every prompt is a new document of one component, so a second request reaches the prompt
     * already on screen, whoever it comes from. What the prompt names has to follow what it
     * answers, or it asks about one app and replies for another. Public so the test that pins
     * that can deliver one.
     */
    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val next = requested(intent) ?: return
        if (!model.supersede(next.uid, next.pid, next.code)) return
        // Only once the model has taken it: a recreation restores the request being asked about,
        // which is the one this prompt kept when it refused a newcomer.
        setIntent(intent)
        request = next
    }

    /** What [intent] asks about, or null when it names no caller to answer. */
    private fun requested(intent: Intent): PermissionRequest? {
        val uid = intent.getIntExtra("uid", -1)
        val pid = intent.getIntExtra("pid", -1)
        val code = intent.getIntExtra("requestCode", -1)
        val ai = intent.getParcelableExtra<ApplicationInfo>("applicationInfo")
        if (uid == -1 || pid == -1 || ai == null) return null
        val label = runCatching { ai.loadLabel(packageManager).toString() }.getOrDefault(ai.packageName)
        return PermissionRequest(uid, pid, code, ai, label)
    }
}

/** One request the prompt can ask about. [ApplicationInfo] brings no equality of its own, so two of
 *  these built from two intents never compare equal, which is what restarts the identity lookup. */
internal class PermissionRequest(
    val uid: Int,
    val pid: Int,
    val code: Int,
    val info: ApplicationInfo,
    val label: String,
)

/** The requesting app as the prompt shows it; [profile] is null for the manager's own user. */
internal data class RequestingApp(
    val label: String,
    val packageName: String,
    val icon: ImageBitmap?,
    val profile: String?,
)

@Composable
internal fun PermissionDialogContent(stage: String, app: RequestingApp, onAllow: () -> Unit, onDeny: () -> Unit, onClose: () -> Unit) {
    DialogSurface(actions = {
        when (stage) {
            "ready" ->
                // IntrinsicSize.Max keeps both buttons the same height when only one label wraps.
                Row(Modifier.padding(top = 16.dp).height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = onDeny, modifier = Modifier.weight(1f).fillMaxHeight()) {
                        Text(stringResource(R.string.grant_dialog_button_deny), textAlign = TextAlign.Center)
                    }
                    FilledTonalButton(onClick = onAllow, modifier = Modifier.weight(1f).fillMaxHeight()) {
                        Text(stringResource(R.string.grant_dialog_button_allow_always), textAlign = TextAlign.Center)
                    }
                }
            "limited" -> Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = onClose) { Text(stringResource(android.R.string.ok)) }
            }
        }
    }) {
        when (stage) {
            "ready" -> {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    // A fixed slot, so the layout does not jump when the icon finishes loading.
                    Box(Modifier.size(48.dp)) {
                        if (app.icon != null) Image(app.icon, contentDescription = null, modifier = Modifier.fillMaxSize())
                        else Icon(Icons.TwoTone.Android, contentDescription = null, modifier = Modifier.fillMaxSize(), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(app.label, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        // The package name is the anti-spoofing anchor: it wraps rather than losing its tail.
                        Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (app.profile != null) Text(app.profile, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HtmlText(stringResource(R.string.permission_warning_template, TextUtils.htmlEncode(app.label), stringResource(R.string.permission_group_description)))
                Text(stringResource(R.string.porter_permission_detail), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            "waiting" -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                Text(stringResource(R.string.porter_permission_waiting), style = MaterialTheme.typography.bodyMedium)
            }
            "limited" -> {
                Text(stringResource(R.string.app_management_dialog_adb_is_limited_title), style = MaterialTheme.typography.titleLarge)
                HtmlText(stringResource(R.string.app_management_dialog_adb_is_limited_message, Helps.ADB_PERMISSION.get()))
            }
            // The user has already answered by the time this arm recomposes; it must not claim to be waiting.
            else -> CircularProgressIndicator()
        }
    }
}

/** The service calls the permission flow depends on; the policy itself stays in [PermissionViewModel]. */
internal interface PermissionGateway {
    fun serviceStates(): Flow<PorterStateMachine.State>
    suspend fun canGrantPermissions(): Boolean
    fun dispatch(uid: Int, pid: Int, code: Int, allowed: Boolean, onetime: Boolean)
}

internal object PorterPermissionGateway : PermissionGateway {
    override fun serviceStates() = PorterStateMachine.instance.asFlow()
    override suspend fun canGrantPermissions() = withContext(Dispatchers.IO) {
        val service = IPorterService.Stub.asInterface(ServerBinder.require())
        service.uid == 0 || service.checkPermission("android.permission.GRANT_RUNTIME_PERMISSIONS") == PackageManager.PERMISSION_GRANTED
    }
    override fun dispatch(uid: Int, pid: Int, code: Int, allowed: Boolean, onetime: Boolean) =
        ServerBinder.manager().dispatchPermissionConfirmationResult(uid, pid, code, allowed, onetime)
}

class PermissionViewModel internal constructor(private val savedState: SavedStateHandle, private val gateway: PermissionGateway) : ViewModel() {
    constructor(savedState: SavedStateHandle) : this(savedState, gatewayOverride ?: PorterPermissionGateway)
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
                withTimeout(SERVICE_TIMEOUT) { gateway.serviceStates().first { it == PorterStateMachine.State.RUNNING } }
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
    /** Whether this prompt is still in a position to ask, which is while it is on screen unanswered. */
    private val asking get() = initialized && !gate.replied && (stage.value == "waiting" || stage.value == "ready")

    /**
     * Points this prompt at a newer request, and reports whether it now asks about it.
     *
     * Two requests, one prompt, and a caller suspended on each, so whichever is not shown is
     * answered here or waits out its own process. While the prompt can still ask, that is the
     * request being replaced. Once it cannot, it is the newcomer, and what answers it is the
     * decision already made, which covers every request from the uid it was made about.
     */
    fun supersede(uid: Int, pid: Int, code: Int): Boolean {
        if (this.uid == uid && this.pid == pid && this.code == code) return asking
        if (asking) {
            answer(this.uid, this.pid, this.code, allowed = false)
            this.uid = uid; this.pid = pid; this.code = code
            return true
        }
        answer(uid, pid, code, allowed = uid == this.uid && savedState.get<Boolean>("allowed") == true)
        return false
    }

    /** Dispatches for a request the user was never shown, so outside [gate] and its one decision. */
    private fun answer(uid: Int, pid: Int, code: Int, allowed: Boolean) {
        try { gateway.dispatch(uid, pid, code, allowed = allowed, onetime = !allowed) }
        catch (e: Exception) { LOGGER.e(e, "dispatchPermissionConfirmationResult") }
    }

    fun reply(allowed: Boolean, limited: Boolean = false) {
        gate.reply {
            savedState["replied"] = true
            // Kept because it outlives the prompt: a request from the same uid arriving after
            // this is covered by it, and a refusal instead would undo it.
            savedState["allowed"] = allowed
            savedState["stage"] = if (limited) "limited" else "finished"
            // A denial is one-time: the user is asked again next time, "don't ask again" is
            // Porter's own screen.
            try { gateway.dispatch(uid, pid, code, allowed = allowed, onetime = !allowed) }
            catch (e: Exception) { LOGGER.e(e, "dispatchPermissionConfirmationResult") }
        }
    }
    companion object {
        const val SERVICE_TIMEOUT = 5000L
        /** Test seam for the activity's default view-model factory; production leaves it null. */
        internal var gatewayOverride: PermissionGateway? = null
    }
}
