package eu.darken.porter.manager

import android.content.SharedPreferences
import android.os.Build

object LocaleMigration {

    fun needsClear(prefs: SharedPreferences): Boolean =
        Build.VERSION.SDK_INT >= 33 &&
            !prefs.getBoolean(PorterSettings.Keys.KEY_SYSTEM_LOCALE_MIGRATED, false)

    fun markDone(prefs: SharedPreferences) {
        prefs.edit().putBoolean(PorterSettings.Keys.KEY_SYSTEM_LOCALE_MIGRATED, true).apply()
    }
}
