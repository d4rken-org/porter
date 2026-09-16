package eu.darken.porter.manager.utils;

import android.os.SystemProperties;
import android.text.TextUtils;

public final class DeviceCompatibility {

    private static Boolean isMiui;

    private DeviceCompatibility() {
    }

    public static boolean isMiui() {
        if (isMiui == null) {
            isMiui = !TextUtils.isEmpty(SystemProperties.get("ro.miui.ui.version.name"));
        }
        return isMiui;
    }
}
