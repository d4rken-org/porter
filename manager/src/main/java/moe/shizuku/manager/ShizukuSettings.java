package moe.shizuku.manager;

import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.text.TextUtils;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import java.lang.annotation.Retention;
import java.util.Locale;
import moe.shizuku.manager.service.WatchdogService;
import moe.shizuku.manager.receiver.BootCompleteReceiver;
import moe.shizuku.manager.utils.Token;
import moe.shizuku.manager.utils.EmptySharedPreferencesImpl;
import moe.shizuku.manager.utils.EnvironmentUtils;
import static java.lang.annotation.RetentionPolicy.SOURCE;

public class ShizukuSettings {

    public static final String NAME = "settings";
    public static class Keys {
        public static final String KEY_START_ON_BOOT = "start_on_boot";
        public static final String KEY_WATCHDOG = "watchdog";
        public static final String KEY_AUTO_UPDATE_SERVICE = "auto_update_service";
        public static final String KEY_TCP_PORT = "tcp_port";
        public static final String KEY_LANGUAGE = "language";
        public static final String KEY_TRANSLATION = "translation";
        public static final String KEY_TRANSLATION_CONTRIBUTORS = "translation_contributors";
        public static final String KEY_THEME_STYLE = "theme_style";
        public static final String KEY_THEME_COLOR = "theme_color";
        public static final String KEY_NIGHT_MODE = "night_mode";
        public static final String KEY_BLACK_NIGHT_THEME = "black_night_theme";
        public static final String KEY_USE_SYSTEM_COLOR = "use_system_color";
        public static final String KEY_HELP = "help";
        public static final String KEY_REPORT_BUG = "report_bug";
        public static final String KEY_LEGACY_PAIRING = "legacy_pairing";
        public static final String KEY_CATEGORY_ADVANCED = "category_advanced";
    }

    private static SharedPreferences sPreferences;

    public static SharedPreferences getPreferences() {
        return sPreferences;
    }

    @NonNull
    private static Context getSettingsStorageContext(@NonNull Context context) {
        Context storageContext;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            storageContext = context.createDeviceProtectedStorageContext();
        } else {
            storageContext = context;
        }

        storageContext = new ContextWrapper(storageContext) {
            @Override
            public SharedPreferences getSharedPreferences(String name, int mode) {
                try {
                    return super.getSharedPreferences(name, mode);
                } catch (IllegalStateException e) {
                    // SharedPreferences in credential encrypted storage are not available until after user is unlocked
                    return new EmptySharedPreferencesImpl();
                }
            }
        };

        return storageContext;
    }

    public static void initialize(Context context) {
        if (sPreferences == null) {
            sPreferences = getSettingsStorageContext(context)
                .getSharedPreferences(NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = sPreferences.edit().remove(Keys.KEY_LANGUAGE);
            if (!sPreferences.contains(Keys.KEY_THEME_STYLE)) {
                editor.putString(Keys.KEY_THEME_STYLE,
                    Build.VERSION.SDK_INT >= 31 && !sPreferences.getAll().isEmpty() && sPreferences.getBoolean(Keys.KEY_USE_SYSTEM_COLOR, true)
                        ? "MATERIAL_YOU" : "DEFAULT");
            }
            if (!sPreferences.contains(Keys.KEY_THEME_COLOR)) {
                editor.putString(Keys.KEY_THEME_COLOR,
                    sPreferences.getBoolean(Keys.KEY_BLACK_NIGHT_THEME, false) ? "AMOLED" : "BLUE");
            }
            editor.apply();
        }
    }

    @IntDef({
        LaunchMethod.UNKNOWN,
        LaunchMethod.ROOT,
        LaunchMethod.ADB,
    })
    @Retention(SOURCE)
    public @interface LaunchMethod {
        int UNKNOWN = -1;
        int ROOT = 0;
        int ADB = 1;
    }

    @LaunchMethod
    public static int getLastLaunchMode() {
        return getPreferences().getInt("mode", LaunchMethod.UNKNOWN);
    }

    public static void setLastLaunchMode(@LaunchMethod int method) {
        getPreferences().edit().putInt("mode", method).apply();
    }

    public static String getAuthToken() {
        String authToken = getPreferences().getString("auth_token", null);
        if (authToken == null || authToken.isEmpty()) {
            authToken = generateAuthToken();
        }
        return authToken;
    }

    public static String generateAuthToken() {
        String token = Token.generateToken();
        getPreferences().edit().putString("auth_token", token).apply();
        return token;
    }

    public static boolean getStartOnBoot(Context context) {
        ComponentName bootCompleteReceiver = new ComponentName(context.getPackageName(), BootCompleteReceiver.class.getName());
        int state = context.getPackageManager().getComponentEnabledSetting(bootCompleteReceiver);
        return state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    }

    public static void setStartOnBoot(Context context, boolean enable) {
        ComponentName bootCompleteReceiver = new ComponentName(context.getPackageName(), BootCompleteReceiver.class.getName());
        context.getPackageManager().setComponentEnabledSetting(
            bootCompleteReceiver,
            enable ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        );
        getPreferences().edit().putBoolean(Keys.KEY_START_ON_BOOT, enable).apply();
    }
    
    public static boolean getWatchdog() {
        return getPreferences().getBoolean(Keys.KEY_WATCHDOG, false);
    }

    public static boolean getAutoUpdateService() {
        return getPreferences().getBoolean(Keys.KEY_AUTO_UPDATE_SERVICE, false);
    }

    public static boolean isWatchdogRunning() {
        return WatchdogService.isRunning();
    }

    public static void setWatchdog(Context context, boolean enable) {
        if (enable) {
            WatchdogService.start(context);
        } else {
            WatchdogService.stop(context);
        }
        getPreferences().edit().putBoolean(Keys.KEY_WATCHDOG, enable).apply();
        return;
    }

    public static int getTcpPort() {
        try {
            return Integer.parseInt(getPreferences().getString(Keys.KEY_TCP_PORT, "5555"));
        } catch (NumberFormatException e) {
            return 5555;
        }
    }

    public static void setTcpPort(@Nullable Integer port) {
        if (port != null) {
            getPreferences().edit().putString(Keys.KEY_TCP_PORT, Integer.toString(port)).apply();
        } else {
            getPreferences().edit().remove(Keys.KEY_TCP_PORT).apply();
        }
        
    }

    public static boolean getLegacyPairing() {
        return getPreferences().getBoolean(Keys.KEY_LEGACY_PAIRING, false);
    }

    @AppCompatDelegate.NightMode
    public static int getNightMode() {
        return getPreferences().getInt(Keys.KEY_NIGHT_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }

    public static Locale getLocale() {
        return android.content.res.Resources.getSystem().getConfiguration().getLocales().get(0);
    }
}
