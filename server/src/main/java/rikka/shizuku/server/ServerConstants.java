package rikka.shizuku.server;

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

    public static final int BINDER_TRANSACTION_getDiagnostics = 10002;
    public static final int BINDER_TRANSACTION_getApplications = 10001;
}
