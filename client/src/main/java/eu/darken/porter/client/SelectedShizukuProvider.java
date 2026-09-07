package eu.darken.porter.client;

import android.os.Bundle;
import rikka.shizuku.ShizukuProvider;

/** Replaces ShizukuProvider at ${applicationId}.shizuku when an app supports both backends. */
public final class SelectedShizukuProvider extends ShizukuProvider {
    @Override
    public boolean onCreate() {
        if (PorterClient.getActiveBackend(getContext()) == PorterClient.Backend.PORTER) {
            disableAutomaticSuiInitialization();
        }
        return super.onCreate();
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!METHOD_GET_BINDER.equals(method)
                && PorterClient.getActiveBackend(getContext()) != PorterClient.Backend.SHIZUKU) return null;
        return super.call(method, arg, extras);
    }
}
