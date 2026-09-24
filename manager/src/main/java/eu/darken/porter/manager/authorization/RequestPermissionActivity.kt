package eu.darken.porter.manager.authorization

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
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
import eu.darken.porter.manager.PorterSettings
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

    /**
     * When the app this prompt names last changed, as of the composition that drew the new one.
     *
     * [PermissionViewModel.supersede] changes which request a tap grants before the frame naming
     * the new app has been drawn, so a tap already on its way answers for an app the user was not
     * looking at, and it is stamped where the new name is composed rather than where its intent
     * arrived, because the grace has to run from the frame the user can read.
     *
     * Null while the prompt has only ever named one app. A prompt that never changed app has
     * nothing to protect against, and an elapsed-realtime baseline of zero would swallow taps for
     * the first half second after boot.
     */
    private var supersededAt: Long? = null

    /** The uid the last composition named, which is not [request] until that composition has run. */
    private var drawnUid: Int? = null

    /**
     * When the composition that put the buttons on screen was applied; null while they are not.
     *
     * A prompt that answers its first tap lets another app start it just under a finger that is
     * already coming down.
     */
    private var answerableAt: Long? = null

    /**
     * Whether a tap now decides the request the user was shown, rather than one that just arrived.
     *
     * A change of app takes two conditions, because the timer alone cannot cover its own start:
     * [PermissionViewModel.supersede] repoints the model the moment the intent lands, so a tap
     * already queued would answer for an app no frame has named yet. Comparing what is pointed at
     * with what was drawn rules that out, and it holds however many requests arrive between two
     * frames.
     *
     * Internal so a test can ask it directly, as [onNewIntent] is public so a test can deliver one:
     * driving it through a click recomposes first, which is the state it exists to reject.
     */
    internal fun userIsAnswering(): Boolean {
        val asked = request
        if (asked != null && asked.uid != drawnUid) {
            LOGGER.w("Ignoring a tap: repointed to ${'$'}{asked.uid}, screen still shows ${'$'}drawnUid")
            return false
        }
        val shown = answerableAt
        if (shown == null) {
            LOGGER.w("Ignoring a tap before the buttons were drawn")
            return false
        }
        val sinceShown = SystemClock.elapsedRealtime() - shown
        if (sinceShown < SUPERSEDE_GRACE) {
            LOGGER.w("Ignoring a tap $sinceShown ms after the buttons appeared")
            return false
        }
        val changed = supersededAt ?: return true
        val since = SystemClock.elapsedRealtime() - changed
        if (since >= SUPERSEDE_GRACE) return true
        LOGGER.w("Ignoring a tap ${'$'}since ms after the prompt changed request")
        return false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false)
        // The saved copy first: a rebuilt process is handed the intent that launched the task,
        // which names the request this prompt had already replaced.
        val first = restored(savedInstanceState) ?: requested(intent)
        if (first == null) { finish(); return }
        request = first
        model.initialize(first.uid, first.pid, first.code)
        porterContent {
            val asked = request ?: return@porterContent
            val ai = asked.info
            val stage by model.stage.collectAsStateWithLifecycle()
            // Applied before the frame that draws the buttons, so no tap can reach them unstamped.
            SideEffect { answerableAt = if (stage == "ready") answerableAt ?: SystemClock.elapsedRealtime() else null }
            // Keyed on the request, so the name and icon reset in the composition a replacement
            // causes rather than when the coroutine below has decoded the new icon. An icon is as
            // big as the app that ships it, so that wait is not ours to bound.
            val identityState = remember(asked) {
                // The id is a local calculation, so it is on screen with the name. Two copies of
                // one package in different profiles are otherwise identical down to the icon, and
                // the row that tells them apart would arrive only after the service answered.
                val userId = UserHandleCompat.getUserId(asked.uid)
                val known = userId != UserHandleCompat.myUserId()
                mutableStateOf(RequestingApp(asked.label, ai.packageName, null, if (known) "$userId" else null, asked.alsoCovers))
            }
            val identity = identityState.value
            LaunchedEffect(asked) {
                // The grace starts here, at the composition that puts the new name on screen.
                // Compared against what was last drawn rather than what was last delivered, so a
                // burst that lands between two frames still counts as the one change it looks like.
                // An app asking again names itself, so it never arms: suppressing those taps would
                // let it ask on a timer to keep the prompt unanswerable, with back swallowed.
                if (drawnUid != null && drawnUid != asked.uid) supersededAt = SystemClock.elapsedRealtime()
                drawnUid = asked.uid
                val icon = withContext(Dispatchers.IO) { runCatching { ai.loadIcon(packageManager).toBitmap(96, 96).asImageBitmap() }.getOrNull() }
                val userId = UserHandleCompat.getUserId(asked.uid)
                val ours = userId == UserHandleCompat.myUserId()
                identityState.value = RequestingApp(asked.label, ai.packageName, icon, if (ours) null else "$userId", asked.alsoCovers)
                if (ours) return@LaunchedEffect
                // The name comes from the service, so only it waits. No answer within the bound
                // leaves the bare id standing, which is honest where a guessed name is not.
                val profile = try {
                    withTimeout(PermissionViewModel.SERVICE_TIMEOUT) {
                        PorterStateMachine.instance.asFlow().first { it == PorterStateMachine.State.RUNNING }
                        withContext(Dispatchers.IO) { runCatching { PorterSystemApis.instance.getUserInfo(userId).let { "${it.name} ($userId)" } }.getOrNull() }
                    }
                } catch (e: TimeoutCancellationException) {
                    LOGGER.e(e, "Binder not received in 5s, requesting user not named")
                    null
                } ?: return@LaunchedEffect
                identityState.value = RequestingApp(asked.label, ai.packageName, icon, profile, asked.alsoCovers)
            }
            BackHandler(enabled = stage == "waiting" || stage == "ready") {}
            LaunchedEffect(stage) { if (stage == "finished") finish() }
            PermissionDialogContent(
                stage,
                identity,
                onAllow = { if (userIsAnswering()) model.reply(true) },
                onDeny = { if (userIsAnswering()) model.reply(false) },
                onClose = { finish() },
            )
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
        // Only once the model has taken it. setIntent covers a configuration change; a process
        // death is handed the launching intent instead, which is what onSaveInstanceState is for.
        setIntent(intent)
        request = next
    }

    /**
     * The copy a rebuilt process is restored from. Public so the test that pins it can read what
     * this writes, as [onNewIntent] is public so a test can deliver one.
     */
    public override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // The request this prompt owes an answer to, which is not the one in the launching intent
        // once onNewIntent has replaced it.
        val asked = request ?: return
        outState.putInt(SAVED_UID, asked.uid)
        outState.putInt(SAVED_PID, asked.pid)
        outState.putInt(SAVED_CODE, asked.code)
        outState.putParcelable(SAVED_INFO, asked.info)
        outState.putStringArray(SAVED_PACKAGES, asked.packages.toTypedArray())
    }

    /**
     * What a rebuilt process was asking about, or null where it is starting fresh.
     *
     * Internal so a test can read it without standing up a raw activity, which leaks a window into
     * the JVM the rest of the module's tests share.
     */
    internal fun restored(state: Bundle?): PermissionRequest? {
        val saved = state ?: return null
        val uid = saved.getInt(SAVED_UID, -1)
        val pid = saved.getInt(SAVED_PID, -1)
        @Suppress("DEPRECATION") val ai = saved.getParcelable<ApplicationInfo>(SAVED_INFO)
        if (uid == -1 || pid == -1 || ai == null) return null
        return PermissionRequest(uid, pid, saved.getInt(SAVED_CODE, -1), ai, labelOf(ai), saved.getStringArray(SAVED_PACKAGES)?.toList().orEmpty())
    }

    private fun labelOf(ai: ApplicationInfo): String {
        val label = runCatching {
            val line = safeLabel(ai.loadLabel(packageManager))
            // The platform's pass reads the label as HTML, which joins lines rather than cutting at
            // the first and turns entities such as &#x202E; back into direction controls, so
            // safeLabel runs on both sides of it.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                safeLabel(TextUtils.makeSafeForPresentation(line, MAX_LABEL_LENGTH, 0f, TextUtils.SAFE_STRING_FLAG_TRIM or TextUtils.SAFE_STRING_FLAG_FIRST_LINE))
            } else {
                line
            }
        }.getOrNull()
        return if (label.isNullOrEmpty()) ai.packageName else label
    }

    internal companion object {
        private const val SAVED_UID = "asked.uid"
        private const val SAVED_PID = "asked.pid"
        private const val SAVED_CODE = "asked.code"
        private const val SAVED_INFO = "asked.info"
        private const val SAVED_PACKAGES = "asked.packages"

        /**
         * How long after the buttons appear, or the prompt changes request, a tap is ignored for.
         * Matches what the platform permission dialog allows itself for the same reason.
         */
        const val SUPERSEDE_GRACE = 500L
    }

    /** What [intent] asks about, or null when it names no caller to answer. */
    private fun requested(intent: Intent): PermissionRequest? {
        val uid = intent.getIntExtra("uid", -1)
        val pid = intent.getIntExtra("pid", -1)
        val code = intent.getIntExtra("requestCode", -1)
        val ai = intent.getParcelableExtra<ApplicationInfo>("applicationInfo")
        if (uid == -1 || pid == -1 || ai == null) return null
        if (ai.uid != uid) {
            LOGGER.w("Ignoring a request from uid %d that names %s of uid %d", uid, ai.packageName, ai.uid)
            return null
        }
        val packages = intent.getStringArrayExtra("packages")?.toList().orEmpty()
        return PermissionRequest(uid, pid, code, ai, labelOf(ai), packages)
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
    val packages: List<String> = emptyList(),
) {
    /** The uid's other packages, which the prompt names because a grant reaches them too. */
    val alsoCovers get() = packages.filter { it != info.packageName }.distinct()
}

/** The requesting app as the prompt shows it; [profile] is null for the manager's own user. */
internal data class RequestingApp(
    val label: String,
    val packageName: String,
    val icon: ImageBitmap?,
    val profile: String?,
    val alsoCovers: List<String> = emptyList(),
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
                        if (app.alsoCovers.isNotEmpty()) Text(stringResource(R.string.porter_permission_also_covers, app.alsoCovers.joinToString(", ")), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

    /** Records the user's own answer for [uid]; true when a refusal repeats an earlier one. */
    fun noteAnswer(uid: Int, allowed: Boolean): Boolean
}

internal object PorterPermissionGateway : PermissionGateway {
    override fun serviceStates() = PorterStateMachine.instance.asFlow()
    override suspend fun canGrantPermissions() = withContext(Dispatchers.IO) {
        val service = IPorterService.Stub.asInterface(ServerBinder.require())
        service.uid == 0 || service.checkPermission("android.permission.GRANT_RUNTIME_PERMISSIONS") == PackageManager.PERMISSION_GRANTED
    }
    override fun dispatch(uid: Int, pid: Int, code: Int, allowed: Boolean, onetime: Boolean) =
        ServerBinder.manager().dispatchPermissionConfirmationResult(uid, pid, code, allowed, onetime)
    override fun noteAnswer(uid: Int, allowed: Boolean) = PorterSettings.noteAnswer(uid, allowed)
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
                else reply(false, limited = true, byUser = false)
            } catch (e: TimeoutCancellationException) {
                LOGGER.e(e, "Binder not received in 5s")
                giveUp()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { LOGGER.e(e, "Permission request failed"); giveUp() }
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
        if (this.uid == uid && this.pid == pid && this.code == code) {
            if (asking) return true
            // The same triple from a caller that reuses request codes is a different request with
            // nothing else to answer it. Dispatching twice for one that is merely redelivered is
            // harmless: the SDK has already taken its waiter off the map.
            answer(uid, pid, code, allowed = savedState.get<Boolean>("allowed") == true)
            return false
        }
        if (asking) {
            answer(this.uid, this.pid, this.code, allowed = false)
            this.uid = uid; this.pid = pid; this.code = code
            return true
        }
        answer(uid, pid, code, allowed = uid == this.uid && savedState.get<Boolean>("allowed") == true)
        return false
    }

    /**
     * Abandons the prompt, refusing whatever request it still owes an answer to.
     *
     * Reaching "finished" without dispatching leaves that caller suspended for the life of its
     * process. The request owed an answer is whichever one [supersede] last adopted, which is why
     * this refuses rather than only finishing. One-time, because nothing here is the user deciding.
     */
    private fun giveUp() = reply(false, byUser = false)

    /** Dispatches for a request the user was never shown, so outside [gate] and its one decision. */
    private fun answer(uid: Int, pid: Int, code: Int, allowed: Boolean) {
        try { gateway.dispatch(uid, pid, code, allowed = allowed, onetime = !allowed) }
        catch (e: Exception) { LOGGER.e(e, "dispatchPermissionConfirmationResult") }
    }

    /** [byUser] is false for the refusals the prompt gives on its own, which never count as the user's. */
    fun reply(allowed: Boolean, limited: Boolean = false, byUser: Boolean = true) {
        gate.reply {
            savedState["replied"] = true
            // Kept because it outlives the prompt: a request from the same uid arriving after
            // this is covered by it, and a refusal instead would undo it.
            savedState["allowed"] = allowed
            savedState["stage"] = if (limited) "limited" else "finished"
            // A first denial is one-time, so the user is asked again next time; a repeated one is
            // remembered, so an app cannot keep asking until a tap lands on Allow.
            val repeated = byUser && gateway.noteAnswer(uid, allowed)
            try { gateway.dispatch(uid, pid, code, allowed = allowed, onetime = !allowed && !repeated) }
            catch (e: Exception) { LOGGER.e(e, "dispatchPermissionConfirmationResult") }
        }
    }
    companion object {
        const val SERVICE_TIMEOUT = 5000L
        /** Test seam for the activity's default view-model factory; production leaves it null. */
        internal var gatewayOverride: PermissionGateway? = null
    }
}
