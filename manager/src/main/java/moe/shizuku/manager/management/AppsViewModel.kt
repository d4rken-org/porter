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
        val granted get() = authorization == DiscoveredApplication.ALLOWED
        val canAuthorize get() = connectionStatus in setOf(DiscoveredApplication.DIRECT, DiscoveredApplication.COMPANION,
            DiscoveredApplication.MANAGED_ONLY, DiscoveredApplication.UNKNOWN)
        val canToggle get() = granted || canAuthorize
    }
    data class State(val apps: List<App> = emptyList(), val loading: Boolean = true, val error: Throwable? = null,
                     val failedUsers: List<Int> = emptyList(), val legacy: Boolean = false) {
        val grantedCount get() = apps.count { it.granted }
        val compatibleCount get() = apps.count { it.connectionStatus == DiscoveredApplication.DIRECT || it.connectionStatus == DiscoveredApplication.COMPANION }
        val companionRequiredCount get() = apps.count { it.connectionStatus == DiscoveredApplication.NEEDS_COMPANION }
        val allGranted get() = apps.filter { it.canToggle }.let { it.isNotEmpty() && it.all { app -> app.granted } }
    }
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    private val mutex = Mutex()
    private val context = getApplication<Application>()

    fun load() = viewModelScope.launch { mutex.withLock { refresh() } }
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
                val icon = runCatching { ai.loadIcon(pm).toBitmap(96, 96) }.getOrNull()
                App(ai.packageName, ai.uid, label, icon, entry.authorization, entry.connectionStatus, entry.declaredApis,
                    entry.requiresRoot, entry.lastConnectedAt.takeIf { it > 0 })
            }.sortedBy { it.label.lowercase() }
            mutableState.value = State(apps, false, failedUsers = discovery.failedUsers, legacy = discovery.legacy)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { mutableState.update { it.copy(loading = false, error = e) } }
    }
    fun toggle(app: App, enabled: Boolean) = change(listOf(app), enabled)
    fun toggleAll(enabled: Boolean) = change(state.value.apps, enabled)
    private fun change(apps: List<App>, enabled: Boolean) {
        if (state.value.loading) return
        mutableState.update { it.copy(loading = true) }
        viewModelScope.launch {
            mutex.withLock {
                var failure: Exception? = null
                withContext(Dispatchers.IO) {
                    apps.filter { !enabled || it.canAuthorize }.distinctBy { it.uid }.forEach {
                        try {
                            if (enabled) AuthorizationManager.grant(it.packageName, it.uid)
                            else AuthorizationManager.revoke(it.packageName, it.uid)
                        } catch (e: Exception) { if (e is CancellationException) throw e; failure = e }
                    }
                }
                refresh()
                failure?.let { e -> mutableState.update { it.copy(error = e) } }
            }
        }
    }
    fun clearError() { mutableState.update { it.copy(error = null) } }
}
