package eu.darken.porter.manager.service

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import eu.darken.porter.manager.Manifest
import eu.darken.porter.manager.model.PorterServiceVersion
import eu.darken.porter.manager.model.ServiceStatus
import eu.darken.porter.manager.starter.ServiceReplacement
import eu.darken.porter.manager.support.ServerDiagnostics
import eu.darken.porter.manager.utils.LOGGER
import eu.darken.porter.manager.utils.PorterStateMachine
import eu.darken.porter.manager.utils.PorterSystemApis
import eu.darken.porter.manager.utils.UserHandleCompat
import eu.darken.porter.sdk.Porter
import eu.darken.porter.sdk.PorterConnection

internal data class ServiceSnapshot(
    val status: ServiceStatus = ServiceStatus(),
    val serviceState: PorterStateMachine.State = PorterStateMachine.State.STOPPED,
    val updating: Boolean = false,
    val failed: Boolean = false,
    val primaryUser: Boolean = true,
    val installed: PorterServiceVersion = PorterServiceVersion.installed,
) {
    enum class BuildRelation { MATCHING, DIFFERENT, UNAVAILABLE }
    val running get() = serviceState == PorterStateMachine.State.RUNNING && status.uid != -1
    val busy get() = updating || serviceState == PorterStateMachine.State.STARTING || serviceState == PorterStateMachine.State.STOPPING
    val restricted get() = running && !status.permission
    val buildRelation get() = when {
        status.porterVersion?.buildId.isNullOrBlank() -> BuildRelation.UNAVAILABLE
        status.porterVersion?.matches(installed) == true -> BuildRelation.MATCHING
        else -> BuildRelation.DIFFERENT
    }
    val updateAvailable get() = running && buildRelation != BuildRelation.MATCHING
    val canUpdate get() = updateAvailable && primaryUser && !busy
    val canStop get() = running && !busy
    val canStart get() = !busy && serviceState in setOf(PorterStateMachine.State.STOPPED, PorterStateMachine.State.CRASHED) && primaryUser
}

internal class ServiceStatusRepository private constructor(private val appContext: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val status = MutableStateFlow(ServiceStatus())
    val state = combine(status, PorterStateMachine.instance.asFlow(), ServiceReplacement.get(appContext).state) { value, runtime, update ->
        ServiceSnapshot(value, runtime, update.running, update.failed, UserHandleCompat.myUserId() == 0)
    }.stateIn(scope, SharingStarted.Eagerly, ServiceSnapshot(serviceState = PorterStateMachine.instance.get(), primaryUser = UserHandleCompat.myUserId() == 0))

    init { scope.launch { PorterStateMachine.instance.asFlow().collect { refresh() } } }

    fun refresh() {
        scope.launch {
            mutex.withLock {
                val connection = Porter.connection.value
                val loaded = try { load(connection) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { LOGGER.w(e, "Load service status"); ServiceStatus() }
                status.value = if (connection === Porter.connection.value && PorterStateMachine.instance.isRunning()) loaded else ServiceStatus()
                if (PorterStateMachine.instance.isRunning()) ServiceReplacement.get(appContext).reconcile()
            }
        }
    }

    private fun load(connection: PorterConnection?): ServiceStatus {
        if (!PorterStateMachine.instance.isRunning() || connection == null) {
            return ServiceStatus()
        }

        val uid = connection.uid
        val protocolVersion = connection.serverInfo.version
        val seContext = try {
            connection.seLinuxContext
        } catch (tr: Throwable) {
            LOGGER.w(tr, "getSELinuxContext")
            null
        }
        val permissionTest = connection.checkRemotePermission("android.permission.GRANT_RUNTIME_PERMISSIONS")

        // Before a526d6bb, server will not exit on uninstall, manager installed later will get not permission
        // Run a random remote transaction here, report no permission as not running
        PorterSystemApis.instance.checkPermission(Manifest.permission.API_V23, appContext.packageName, 0)
        val info = try {
            ServerDiagnostics.readInfo(connection.binder)
        } catch (e: Exception) {
            LOGGER.w(e, "Read Porter service version")
            null
        }
        return ServiceStatus(uid, protocolVersion, seContext, permissionTest, info?.version, info?.pid)
    }

    companion object {
        @Volatile private var instance: ServiceStatusRepository? = null
        fun get(context: Context): ServiceStatusRepository = instance ?: synchronized(this) {
            instance ?: ServiceStatusRepository(context.applicationContext).also { instance = it }
        }

        // Deliberately not an instance method: get() would build the process-wide singleton and
        // start its eager status polling against the server this call is about to kill.
        fun stop() {
            PorterStateMachine.instance.set(PorterStateMachine.State.STOPPING)
            runCatching { Porter.connection.value?.exit() ?: error("Porter is not running") }.onFailure { PorterStateMachine.instance.update() }
        }
    }
}
