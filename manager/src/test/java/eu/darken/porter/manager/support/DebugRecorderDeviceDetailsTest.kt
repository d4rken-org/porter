package eu.darken.porter.manager.support

import android.Manifest
import android.app.Application
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import eu.darken.porter.manager.BuildConfig
import eu.darken.porter.manager.PorterApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DebugRecorderDeviceDetailsTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()

    @Before fun setUp() {
        // EnvironmentUtils takes its context from the production application, which no unit test creates.
        ReflectionHelpers.setStaticField(PorterApplication::class.java, "appContext", application)
        val resolver = application.contentResolver
        Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, 0)
        Settings.Global.putInt(resolver, "adb_wifi_enabled", 1)
        Settings.Global.putInt(resolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 2)
    }

    private fun lines() = DebugRecorder.deviceDetails(application).lines()

    @Test fun recordsTheSettingsTheStartGateReads() {
        val lines = lines()
        assertEquals("Porter ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", lines.first())
        assertTrue(lines.toString(), "adb_enabled: 0" in lines)
        assertTrue(lines.toString(), "adb_wifi_enabled: 1" in lines)
        assertTrue(lines.toString(), "development_settings_enabled: 2" in lines)
        assertTrue(lines.toString(), lines.any { Regex("ADB TCP port: -?\\d+").matches(it) })
        assertTrue(lines.toString(), "TLS supported: true" in lines)
    }

    @Test fun recordsWhetherSecureSettingsIsHeld() {
        assertTrue("WRITE_SECURE_SETTINGS: denied" in lines())
        shadowOf(application).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        assertTrue("WRITE_SECURE_SETTINGS: granted" in lines())
    }

    @Test fun omitsTheFullSdkBelowApiThirtySix() {
        assertTrue(lines().none { it.startsWith("SDK_INT_FULL") })
    }

    @Test @Config(sdk = [36]) fun recordsTheFullSdkFromApiThirtySix() {
        val lines = lines()
        assertTrue(lines.toString(), lines.any { Regex("SDK_INT_FULL: \\d+").matches(it) })
    }
}
