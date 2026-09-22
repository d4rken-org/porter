package eu.darken.porter.manager.service

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import eu.darken.porter.manager.ServerBinder
import eu.darken.porter.manager.model.PorterServiceVersion
import eu.darken.porter.manager.model.ServiceStatus
import eu.darken.porter.manager.starter.ServiceReplacement
import eu.darken.porter.manager.support.ServerDiagnostics
import eu.darken.porter.manager.utils.LOGGER
import eu.darken.porter.manager.utils.PorterStateMachine
import eu.darken.porter.manager.utils.UserHandleCompat
import eu.darken.porter.sdk.Porter
import eu.darken.porter.sdk.PorterAvailability
import eu.darken.porter.server.IPorterService
import android.os.IBinder

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
                val binder = ServerBinder.binder.value
                val loaded = try { load(binder) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { LOGGER.w(e, "Load service status"); ServiceStatus() }
                status.value = if (binder === ServerBinder.binder.value && PorterStateMachine.instance.isRunning()) loaded else ServiceStatus()
                if (PorterStateMachine.instance.isRunning()) ServiceReplacement.get(appContext).reconcile()
            }
        }
    }

    private suspend fun load(binder: IBinder?): ServiceStatus {
        if (!PorterStateMachine.instance.isRunning() || binder == null) {
            return ServiceStatus()
        }

        // The SDK holds no connection for a server it cannot speak to; the manager is admitted to
        // these calls whatever the protocol says, so it asks the binder itself then.
        val connection = Porter.connection.value?.takeIf { it.binder === binder }
        val service = IPorterService.Stub.asInterface(binder)
        val uid = connection?.uid ?: service.uid
        val protocolVersion = connection?.serverInfo?.version
            ?: (Porter.availability(appContext) as? PorterAvailability.Incompatible)?.incompatibility?.serverVersion
            ?: 0
        val seContext = try {
            connection?.seLinuxContext ?: service.seLinuxContext
        } catch (tr: Throwable) {
            LOGGER.w(tr, "getSELinuxContext")
            null
        }
        val permissionTest = if (uid == 0) true else {
            service.checkPermission("android.permission.GRANT_RUNTIME_PERMISSIONS") == PackageManager.PERMISSION_GRANTED
        }

        val info = try {
            ServerDiagnostics.readInfo(binder)
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
            runCatching { ServerBinder.manager().exit() }.onFailure { PorterStateMachine.instance.update() }
        }
    }
}
