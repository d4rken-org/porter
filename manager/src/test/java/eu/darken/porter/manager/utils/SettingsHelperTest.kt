package eu.darken.porter.manager.utils

import android.app.Application
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsHelperTest {

    private val application = ApplicationProvider.getApplicationContext<Application>()

    @Test fun `a denied write returns false instead of throwing`() {
        assertFalse(SettingsHelper.tryPutGlobalInt("adb_wifi_enabled") {
            throw SecurityException("Permission denial: writing to settings requires android.permission.WRITE_SECURE_SETTINGS")
        })
    }

    @Test fun `a succeeding write returns true`() {
        assertTrue(SettingsHelper.tryPutGlobalInt("adb_wifi_enabled") { true })
    }

    @Test fun `the default write lands in Settings Global`() {
        val cr = application.contentResolver
        Settings.Global.putInt(cr, "adb_wifi_enabled", 0)

        assertTrue(SettingsHelper.tryPutGlobalInt(cr, "adb_wifi_enabled", 1))
        assertEquals(1, Settings.Global.getInt(cr, "adb_wifi_enabled", -1))
    }

    @Test fun `a trusted disabled setting highlights wireless debugging`() {
        assertEquals(SettingsPage.Developer.HighlightWirelessDebugging, SettingsHelper.wirelessDebuggingPage(0, true))
    }

    @Test fun `an enabled or untrusted setting opens wireless debugging`() {
        assertEquals(SettingsPage.Developer.WirelessDebugging, SettingsHelper.wirelessDebuggingPage(1, true))
        assertEquals(SettingsPage.Developer.WirelessDebugging, SettingsHelper.wirelessDebuggingPage(1, false))
        assertEquals(SettingsPage.Developer.WirelessDebugging, SettingsHelper.wirelessDebuggingPage(0, false))
    }
}
