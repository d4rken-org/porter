package eu.darken.porter.client;

import android.content.Context;
import android.content.pm.PackageManager;

/** Select a backend before connecting; changing an established session requires an app restart. */
public final class PorterClient {
    public static final String PERMISSION = "eu.darken.porter.permission.API_V23";
    private static final String PREFERENCES = "porter.client";
    private static final String BACKEND = "backend";

    public enum Backend { AUTO, PORTER, SHIZUKU }

    private static Backend activeBackend;

    public static Backend getPreferredBackend(Context context) {
        String value = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(BACKEND, Backend.AUTO.name());
        try {
            return Backend.valueOf(value);
        } catch (IllegalArgumentException e) {
            return Backend.AUTO;
        }
    }

    public static synchronized Backend getActiveBackend(Context context) {
        if (activeBackend == null) {
            Backend preferred = getPreferredBackend(context);
            activeBackend = preferred == Backend.AUTO
                    ? (getPorterPackage(context) != null ? Backend.PORTER : Backend.SHIZUKU)
                    : preferred;
        }
        return activeBackend;
    }

    public static boolean setBackendForNextProcess(Context context, Backend backend) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putString(BACKEND, backend.name()).commit();
    }

    public static String getPorterPackage(Context context) {
        try {
            return context.getPackageManager().getPermissionInfo(PERMISSION, 0).packageName;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private PorterClient() {}
}
