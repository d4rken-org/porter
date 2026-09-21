package eu.darken.porter.privileged

import eu.darken.porter.protocol.PorterProtocol

object ServerConstants {

    const val MANAGER_APP_NOT_FOUND = 50

    const val PERMISSION = PorterProtocol.PERMISSION
    const val MANAGER_PERMISSION = "eu.darken.porter.permission.MANAGER"
    const val LEGACY_PERMISSION = "moe.shizuku.manager.permission.API_V23"
    const val COMPAT_APPLICATION_ID = "moe.shizuku.privileged.api"
    const val REQUEST_PERMISSION_ACTION = PorterProtocol.MANAGER_APPLICATION_ID + ".intent.action.REQUEST_PERMISSION"

    const val DIAGNOSTICS_VERSION_NAME = "eu.darken.porter.version.name"
    const val DIAGNOSTICS_VERSION_CODE = "eu.darken.porter.version.code"

    /** Reconciler state; all monotonic milliseconds, comparable only against each other. */
    const val DIAGNOSTICS_RECONCILER_MANAGER_CHECKED = "eu.darken.porter.reconciler.manager_checked"
    const val DIAGNOSTICS_RECONCILER_HOST_SCANNED = "eu.darken.porter.reconciler.host_scanned"
    const val DIAGNOSTICS_RECONCILER_HOST_DEADLINE = "eu.darken.porter.reconciler.host_deadline"
    const val DIAGNOSTICS_RECONCILER_MANAGER_FAILURES = "eu.darken.porter.reconciler.manager_failures"
    const val DIAGNOSTICS_RECONCILER_HOST_FAILURES = "eu.darken.porter.reconciler.host_failures"
    const val DIAGNOSTICS_RECONCILER_LAST_TRIGGER = "eu.darken.porter.reconciler.last_trigger"
}
