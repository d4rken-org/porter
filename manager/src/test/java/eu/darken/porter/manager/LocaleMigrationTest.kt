package eu.darken.porter.manager

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * The one-shot clear of the system locale selection. A user can pick Porter's language in system
 * settings before ever launching it, so only an install that carries settings from the in-app
 * language picker may be cleared.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [34])
class LocaleMigrationTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()

    private fun store(): SharedPreferences = application.createDeviceProtectedStorageContext()
        .getSharedPreferences(PorterSettings.NAME, Context.MODE_PRIVATE)

    @Before fun resetSettings() {
        store().edit().clear().commit()
        ReflectionHelpers.setStaticField(PorterSettings::class.java, "sPreferences", null)
    }

    @Test fun aFreshInstallKeepsTheSystemLocale() {
        PorterSettings.initialize(application)
        assertFalse(LocaleMigration.needsClear(PorterSettings.getPreferences()))
    }

    @Test fun anUpgradeClearsTheLocaleOnce() {
        store().edit().putBoolean(PorterSettings.Keys.KEY_WATCHDOG, true).commit()
        PorterSettings.initialize(application)
        val prefs = PorterSettings.getPreferences()
        assertTrue(LocaleMigration.needsClear(prefs))
        LocaleMigration.markDone(prefs)
        assertFalse(LocaleMigration.needsClear(prefs))
    }

    @Test fun anAlreadyMigratedInstallKeepsTheSystemLocale() {
        store().edit().putBoolean(PorterSettings.Keys.KEY_SYSTEM_LOCALE_MIGRATED, true).commit()
        PorterSettings.initialize(application)
        assertFalse(LocaleMigration.needsClear(PorterSettings.getPreferences()))
    }
}
