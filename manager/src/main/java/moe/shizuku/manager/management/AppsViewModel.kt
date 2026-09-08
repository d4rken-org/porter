package moe.shizuku.manager.management

import android.app.Application
import android.graphics.Bitmap
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
    data class App(val packageName: String, val uid: Int, val label: String, val icon: Bitmap?, val granted: Boolean, val requiresRoot: Boolean)
    data class State(val apps: List<App> = emptyList(), val loading: Boolean = true, val error: Throwable? = null) {
        val grantedCount get() = apps.count { it.granted }
    }
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    private val mutex = Mutex()
    private val context = getApplication<Application>()

    fun load() = viewModelScope.launch { mutex.withLock { refresh() } }
    private suspend fun refresh() = withContext(Dispatchers.IO) {
        try {
            val pm = context.packageManager
            val apps = AuthorizationManager.getPackages(exclude = listOf(context.packageName)).filter { it.packageName != context.packageName }.map { pi ->
                val ai = pi.applicationInfo!!
                val userId = UserHandleCompat.getUserId(ai.uid)
                val label = ai.loadLabel(pm).toString().let { if (userId == UserHandleCompat.myUserId()) it else "$it - ${ShizukuSystemApis.getUserInfo(userId).name} ($userId)" }
                val icon = runCatching { ai.loadIcon(pm).toBitmap(96, 96) }.getOrNull()
                App(pi.packageName, ai.uid, label, icon, AuthorizationManager.granted(pi.packageName, ai.uid), ai.metaData?.getBoolean("moe.shizuku.client.V3_REQUIRES_ROOT") == true)
            }
            mutableState.value = State(apps, false)
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
                    apps.forEach {
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
