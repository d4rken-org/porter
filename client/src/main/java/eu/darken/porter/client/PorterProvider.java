package eu.darken.porter.client;

import android.os.Bundle;
import rikka.shizuku.ShizukuProvider;

/** Declare at ${applicationId}.porter, protected by INTERACT_ACROSS_USERS_FULL. */
public final class PorterProvider extends ShizukuProvider {
    @Override
    public boolean onCreate() {
        if (PorterClient.getActiveBackend(getContext()) == PorterClient.Backend.PORTER) {
            disableAutomaticSuiInitialization();
        }
        return super.onCreate();
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (PorterClient.getActiveBackend(getContext()) != PorterClient.Backend.PORTER) return null;
        return super.call(method, arg, extras);
    }
}
