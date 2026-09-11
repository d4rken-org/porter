package moe.shizuku.manager.management

import android.app.Application
import android.graphics.Bitmap
import eu.darken.porter.common.DiscoveredApplication
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.shizuku.manager.authorization.AuthorizationManager
import moe.shizuku.manager.utils.ShizukuSystemApis
import moe.shizuku.manager.utils.UserHandleCompat

class AppsViewModel(application: Application) : AndroidViewModel(application) {
    data class App(val packageName: String, val uid: Int, val label: String, val icon: Bitmap?,
                   val authorization: Int, val connectionStatus: Int, val declaredApis: Int,
                   val requiresRoot: Boolean, val lastConnectedAt: Long?) {
        val granted get() = authorization == DiscoveredApplication.ALLOWED || authorization == DiscoveredApplication.PENDING_COMPANION
        val canAuthorize get() = connectionStatus in setOf(DiscoveredApplication.DIRECT, DiscoveredApplication.COMPANION,
            DiscoveredApplication.NEEDS_COMPANION, DiscoveredApplication.MANAGED_ONLY, DiscoveredApplication.UNKNOWN)
        val canToggle get() = granted || canAuthorize
    }
    data class State(val apps: List<App> = emptyList(), val loading: Boolean = true, val error: Throwable? = null,
                     val failedUsers: List<Int> = emptyList(), val legacy: Boolean = false, val accessEnabled: Boolean? = null,
                     val pendingAccess: Map<Int, Boolean> = emptyMap(), val pendingGlobalAccess: Boolean? = null) {
        val saving get() = pendingAccess.isNotEmpty() || pendingGlobalAccess != null
        val effectiveAccessEnabled get() = pendingGlobalAccess ?: accessEnabled
        fun isGranted(app: App) = pendingAccess[app.uid] ?: app.granted
        val grantedCount get() = apps.count { it.authorization == DiscoveredApplication.ALLOWED }
        val compatibleCount get() = apps.count { it.connectionStatus == DiscoveredApplication.DIRECT || it.connectionStatus == DiscoveredApplication.COMPANION }
        val companionCount get() = apps.count { it.connectionStatus == DiscoveredApplication.COMPANION }
        val companionRequiredCount get() = apps.count { it.connectionStatus == DiscoveredApplication.NEEDS_COMPANION }
        val pendingCompanionCount get() = apps.count { it.granted && it.connectionStatus == DiscoveredApplication.NEEDS_COMPANION }
    }
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    private val mutex = Mutex()
    private val context = getApplication<Application>()

    private val icons = mutableMapOf<String, Bitmap?>()
    private var nextRevision = 0L
    private var globalRevision = 0L
    private val appRevisions = mutableMapOf<Int, Long>()

    fun load() = viewModelScope.launch { mutex.withLock { mutableState.update { it.copy(loading = true) }; icons.clear(); refresh() } }
    private suspend fun refresh() = withContext(Dispatchers.IO) {
        try {
            val pm = context.packageManager
            val discovery = AuthorizationManager.discover()
            val apps = discovery.apps.filter { it.applicationInfo.packageName != context.packageName }.map { entry ->
                val ai = entry.applicationInfo
                val userId = entry.userId
                val baseLabel = runCatching { ai.loadLabel(pm).toString() }.getOrDefault(ai.packageName)
                val label = if (userId == UserHandleCompat.myUserId()) baseLabel
                    else "$baseLabel - ${ShizukuSystemApis.getUserInfo(userId).name} ($userId)"
                val iconKey = "${ai.uid}:${ai.packageName}:${ai.sourceDir}"
                val icon = if (icons.containsKey(iconKey)) icons[iconKey] else {
                    runCatching { ai.loadIcon(pm).toBitmap(96, 96) }.getOrNull().also { icons[iconKey] = it }
                }
                App(ai.packageName, ai.uid, label, icon, entry.authorization, entry.connectionStatus, entry.declaredApis,
                    entry.requiresRoot, entry.lastConnectedAt.takeIf { it > 0 })
            }.sortedBy { it.label.lowercase() }
            val accessEnabled = AuthorizationManager.getGlobalAccess()
            mutableState.update { it.copy(apps = apps, loading = false, error = null,
                failedUsers = discovery.failedUsers, legacy = discovery.legacy, accessEnabled = accessEnabled) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { mutableState.update { it.copy(loading = false, error = e) } }
    }
    fun toggle(app: App, enabled: Boolean) {
        if (state.value.loading || (enabled && !app.canAuthorize)) return
        val revision = ++nextRevision
        appRevisions[app.uid] = revision
        mutableState.update { it.copy(pendingAccess = it.pendingAccess + (app.uid to enabled)) }
        viewModelScope.launch {
            mutex.withLock {
                if (appRevisions[app.uid] != revision) return@withLock
                var failure: Exception? = null
                try {
                    withContext(Dispatchers.IO) {
                        if (enabled) AuthorizationManager.grant(app.packageName, app.uid)
                        else AuthorizationManager.revoke(app.packageName, app.uid)
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { failure = e }
                refresh()
                if (appRevisions[app.uid] == revision) {
                    appRevisions.remove(app.uid)
                    mutableState.update { it.copy(pendingAccess = it.pendingAccess - app.uid, error = failure ?: it.error) }
                }
            }
        }
    }

    fun setGlobalAccess(enabled: Boolean) {
        if (state.value.loading || state.value.accessEnabled == null) return
        val revision = ++nextRevision
        globalRevision = revision
        mutableState.update { it.copy(pendingGlobalAccess = enabled) }
        viewModelScope.launch {
            mutex.withLock {
                if (globalRevision != revision) return@withLock
                var failure: Exception? = null
                try { withContext(Dispatchers.IO) { AuthorizationManager.setGlobalAccess(enabled) } }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { failure = e }
                refresh()
                if (globalRevision == revision) {
                    mutableState.update { it.copy(pendingGlobalAccess = null, error = failure ?: it.error) }
                }
            }
        }
    }
    fun clearError() { mutableState.update { it.copy(error = null) } }
}
