package eu.darken.porter.privileged;

import moe.shizuku.server.BuildConfig;

public class ServerConstants {

    public static final int MANAGER_APP_NOT_FOUND = 50;

    public static final String PERMISSION = "eu.darken.porter.permission.API_V23";
    public static final String MANAGER_PERMISSION = "eu.darken.porter.permission.MANAGER";
    public static final String LEGACY_PERMISSION = "moe.shizuku.manager.permission.API_V23";
    public static final String COMPAT_APPLICATION_ID = "moe.shizuku.privileged.api";
    public static final String REQUEST_PERMISSION_ACTION = BuildConfig.MANAGER_APPLICATION_ID + ".intent.action.REQUEST_PERMISSION";

    public static final String DIAGNOSTICS_VERSION_NAME = "eu.darken.porter.version.name";
    public static final String DIAGNOSTICS_VERSION_CODE = "eu.darken.porter.version.code";

    /** Reconciler state; all monotonic milliseconds, comparable only against each other. */
    public static final String DIAGNOSTICS_RECONCILER_MANAGER_CHECKED = "eu.darken.porter.reconciler.manager_checked";
    public static final String DIAGNOSTICS_RECONCILER_HOST_SCANNED = "eu.darken.porter.reconciler.host_scanned";
    public static final String DIAGNOSTICS_RECONCILER_HOST_DEADLINE = "eu.darken.porter.reconciler.host_deadline";
    public static final String DIAGNOSTICS_RECONCILER_MANAGER_FAILURES = "eu.darken.porter.reconciler.manager_failures";
    public static final String DIAGNOSTICS_RECONCILER_HOST_FAILURES = "eu.darken.porter.reconciler.host_failures";
    public static final String DIAGNOSTICS_RECONCILER_LAST_TRIGGER = "eu.darken.porter.reconciler.last_trigger";

    public static final int BINDER_TRANSACTION_getDiagnostics = 10002;
    public static final int BINDER_TRANSACTION_getApplications = 10001;
    public static final int BINDER_TRANSACTION_setDebugLogging = 10006;
}
