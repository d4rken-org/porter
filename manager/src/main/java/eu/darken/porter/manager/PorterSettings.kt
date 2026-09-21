package eu.darken.porter.manager

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Build
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatDelegate
import eu.darken.porter.manager.receiver.BootCompleteReceiver
import eu.darken.porter.manager.service.WatchdogService
import eu.darken.porter.manager.utils.EmptySharedPreferencesImpl
import eu.darken.porter.manager.utils.Token
import java.util.Locale

object PorterSettings {

    const val NAME = "settings"

    object Keys {
        const val KEY_START_ON_BOOT = "start_on_boot"
        const val KEY_WATCHDOG = "watchdog"
        const val KEY_AUTO_UPDATE_SERVICE = "auto_update_service"
        const val KEY_TCP_PORT = "tcp_port"
        const val KEY_LANGUAGE = "language"
        const val KEY_TRANSLATION = "translation"
        const val KEY_TRANSLATION_CONTRIBUTORS = "translation_contributors"
        const val KEY_THEME_STYLE = "theme_style"
        const val KEY_THEME_COLOR = "theme_color"
        const val KEY_NIGHT_MODE = "night_mode"
        const val KEY_BLACK_NIGHT_THEME = "black_night_theme"
        const val KEY_USE_SYSTEM_COLOR = "use_system_color"
        const val KEY_HELP = "help"
        const val KEY_REPORT_BUG = "report_bug"
        const val KEY_LEGACY_PAIRING = "legacy_pairing"
        const val KEY_CATEGORY_ADVANCED = "category_advanced"
        const val KEY_SYSTEM_LOCALE_MIGRATED = "system_locale_migrated"
    }

    private var storage: SharedPreferences? = null

    val preferences: SharedPreferences
        get() = checkNotNull(storage) { "PorterSettings.initialize was not called" }

    private fun getSettingsStorageContext(context: Context): Context {
        val storageContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext()
        } else {
            context
        }

        return object : ContextWrapper(storageContext) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = try {
                super.getSharedPreferences(name, mode)
            } catch (e: IllegalStateException) {
                // SharedPreferences in credential encrypted storage are not available until after user is unlocked
                EmptySharedPreferencesImpl()
            }
        }
    }

    fun initialize(context: Context) {
        if (storage != null) return
        val preferences = getSettingsStorageContext(context).getSharedPreferences(NAME, Context.MODE_PRIVATE)
        storage = preferences
        val freshInstall = preferences.all.isEmpty()
        val editor = preferences.edit().remove(Keys.KEY_LANGUAGE)
        if (freshInstall) {
            editor.putBoolean(Keys.KEY_SYSTEM_LOCALE_MIGRATED, true)
        }
        if (!preferences.contains(Keys.KEY_THEME_STYLE)) {
            editor.putString(
                Keys.KEY_THEME_STYLE,
                if (Build.VERSION.SDK_INT >= 31 && preferences.all.isNotEmpty() && preferences.getBoolean(Keys.KEY_USE_SYSTEM_COLOR, true)) {
                    "MATERIAL_YOU"
                } else {
                    "DEFAULT"
                },
            )
        }
        if (!preferences.contains(Keys.KEY_THEME_COLOR)) {
            editor.putString(
                Keys.KEY_THEME_COLOR,
                if (preferences.getBoolean(Keys.KEY_BLACK_NIGHT_THEME, false)) "AMOLED" else "BLUE",
            )
        }
        editor.apply()
    }

    /** Forgets the storage, so a test can initialize against a cleared one. */
    @VisibleForTesting
    internal fun resetForTest() {
        storage = null
    }

    /** How the service was last started, stored as the code the Java constants used. */
    enum class LaunchMethod(val code: Int) {
        UNKNOWN(-1),
        ROOT(0),
        ADB(1),
        ;

        companion object {
            fun fromCode(code: Int): LaunchMethod = entries.firstOrNull { it.code == code } ?: UNKNOWN
        }
    }

    var lastLaunchMode: LaunchMethod
        get() = LaunchMethod.fromCode(preferences.getInt("mode", LaunchMethod.UNKNOWN.code))
        set(method) = preferences.edit().putInt("mode", method.code).apply()

    /** The stored token, generated and stored on first use. */
    val authToken: String
        get() = preferences.getString("auth_token", null)?.takeIf { it.isNotEmpty() } ?: generateAuthToken()

    fun generateAuthToken(): String {
        val token = Token.generateToken()
        preferences.edit().putString("auth_token", token).apply()
        return token
    }

    fun isStartOnBoot(context: Context): Boolean {
        val bootCompleteReceiver = ComponentName(context.packageName, BootCompleteReceiver::class.java.name)
        val state = context.packageManager.getComponentEnabledSetting(bootCompleteReceiver)
        return state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }

    fun setStartOnBoot(context: Context, enable: Boolean) {
        val bootCompleteReceiver = ComponentName(context.packageName, BootCompleteReceiver::class.java.name)
        context.packageManager.setComponentEnabledSetting(
            bootCompleteReceiver,
            if (enable) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
        preferences.edit().putBoolean(Keys.KEY_START_ON_BOOT, enable).apply()
    }

    val watchdog: Boolean
        get() = preferences.getBoolean(Keys.KEY_WATCHDOG, false)

    val autoUpdateService: Boolean
        get() = preferences.getBoolean(Keys.KEY_AUTO_UPDATE_SERVICE, false)

    val isWatchdogRunning: Boolean
        get() = WatchdogService.isRunning()

    fun setWatchdog(context: Context, enable: Boolean) {
        if (enable) {
            WatchdogService.start(context)
        } else {
            WatchdogService.stop(context)
        }
        setWatchdogPreference(enable)
    }

    fun setWatchdogPreference(enable: Boolean) {
        preferences.edit().putBoolean(Keys.KEY_WATCHDOG, enable).apply()
    }

    val tcpPort: Int
        get() = preferences.getString(Keys.KEY_TCP_PORT, "5555")?.toIntOrNull() ?: 5555

    fun setTcpPort(port: Int?) {
        if (port != null) {
            preferences.edit().putString(Keys.KEY_TCP_PORT, port.toString()).apply()
        } else {
            preferences.edit().remove(Keys.KEY_TCP_PORT).apply()
        }
    }

    val legacyPairing: Boolean
        get() = preferences.getBoolean(Keys.KEY_LEGACY_PAIRING, false)

    @get:AppCompatDelegate.NightMode
    val nightMode: Int
        get() = preferences.getInt(Keys.KEY_NIGHT_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

    val locale: Locale
        get() = Resources.getSystem().configuration.locales.get(0)
}
