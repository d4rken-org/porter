package moe.shizuku.manager.service

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.shizuku.manager.Manifest
import moe.shizuku.manager.model.PorterServiceVersion
import moe.shizuku.manager.model.ServiceStatus
import moe.shizuku.manager.starter.ServiceReplacement
import moe.shizuku.manager.support.ServerDiagnostics
import moe.shizuku.manager.utils.Logger.LOGGER
import moe.shizuku.manager.utils.ShizukuStateMachine
import moe.shizuku.manager.utils.ShizukuSystemApis
import moe.shizuku.manager.utils.UserHandleCompat
import rikka.shizuku.Shizuku

internal data class ServiceSnapshot(
    val status: ServiceStatus = ServiceStatus(),
    val serviceState: ShizukuStateMachine.State = ShizukuStateMachine.State.STOPPED,
    val updating: Boolean = false,
    val failed: Boolean = false,
    val primaryUser: Boolean = true,
    val installed: PorterServiceVersion = PorterServiceVersion.installed,
) {
    enum class BuildRelation { MATCHING, DIFFERENT, UNAVAILABLE }
    val running get() = serviceState == ShizukuStateMachine.State.RUNNING && status.uid != -1
    val busy get() = updating || serviceState == ShizukuStateMachine.State.STARTING || serviceState == ShizukuStateMachine.State.STOPPING
    val restricted get() = running && !status.permission
    val buildRelation get() = when {
        status.porterVersion?.buildId.isNullOrBlank() -> BuildRelation.UNAVAILABLE
        status.porterVersion?.matches(installed) == true -> BuildRelation.MATCHING
        else -> BuildRelation.DIFFERENT
    }
    val updateAvailable get() = running && buildRelation != BuildRelation.MATCHING
    val canUpdate get() = updateAvailable && primaryUser && !busy
    val canStop get() = running && !busy
    val canStart get() = !busy && serviceState in setOf(ShizukuStateMachine.State.STOPPED, ShizukuStateMachine.State.CRASHED) && primaryUser
}

internal class ServiceStatusRepository private constructor(private val appContext: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val status = MutableStateFlow(ServiceStatus())
    val state = combine(status, ShizukuStateMachine.asFlow(), ServiceReplacement.state) { value, runtime, update ->
        ServiceSnapshot(value, runtime, update.running, update.failed, UserHandleCompat.myUserId() == 0)
    }.stateIn(scope, SharingStarted.Eagerly, ServiceSnapshot(serviceState = ShizukuStateMachine.get(), primaryUser = UserHandleCompat.myUserId() == 0))

    init { scope.launch { ShizukuStateMachine.asFlow().collect { refresh() } } }

    fun refresh() {
        scope.launch {
            mutex.withLock {
                val binder = Shizuku.getBinder()
                val loaded = try { load() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { LOGGER.w(e, "Load service status"); ServiceStatus() }
                status.value = if (binder == Shizuku.getBinder() && ShizukuStateMachine.isRunning()) loaded else ServiceStatus()
                if (ShizukuStateMachine.isRunning()) ServiceReplacement.reconcile()
            }
        }
    }

    private fun load(): ServiceStatus {
        if (!ShizukuStateMachine.isRunning()) {
            return ServiceStatus()
        }

        val uid = Shizuku.getUid()
        val apiVersion = Shizuku.getVersion()
        val patchVersion = Shizuku.getServerPatchVersion().let { if (it < 0) 0 else it }
        val seContext = if (apiVersion >= 6) {
            try {
                Shizuku.getSELinuxContext()
            } catch (tr: Throwable) {
                LOGGER.w(tr, "getSELinuxContext")
                null
            }
        } else null
        val permissionTest =
            Shizuku.checkRemotePermission("android.permission.GRANT_RUNTIME_PERMISSIONS") == PackageManager.PERMISSION_GRANTED

        // Before a526d6bb, server will not exit on uninstall, manager installed later will get not permission
        // Run a random remote transaction here, report no permission as not running
        ShizukuSystemApis.checkPermission(Manifest.permission.API_V23, appContext.packageName, 0)
        val info = try {
            Shizuku.getBinder()?.let { ServerDiagnostics.readInfo(it) }
        } catch (e: Exception) {
            LOGGER.w(e, "Read Porter service version")
            null
        }
        return ServiceStatus(uid, apiVersion, patchVersion, seContext, permissionTest, info?.version, info?.pid)
    }

    companion object {
        @Volatile private var instance: ServiceStatusRepository? = null
        fun get(context: Context): ServiceStatusRepository = instance ?: synchronized(this) {
            instance ?: ServiceStatusRepository(context.applicationContext).also { instance = it }
        }
    }
}
