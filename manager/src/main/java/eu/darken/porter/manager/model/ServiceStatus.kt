package eu.darken.porter.manager.model

import eu.darken.porter.manager.utils.PorterStateMachine

data class ServiceStatus(
        val uid: Int = -1,
        val protocolVersion: Int = -1,
        val seContext: String? = null,
        val permission: Boolean = false,
        val porterVersion: PorterServiceVersion? = null,
        val pid: Int? = null
) {
    val isRunning: Boolean
        get() = uid != -1 && PorterStateMachine.instance.isRunning()
}