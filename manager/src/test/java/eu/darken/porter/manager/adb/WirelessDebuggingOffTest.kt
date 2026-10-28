package eu.darken.porter.manager.adb

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import eu.darken.porter.manager.PorterSettings
import eu.darken.porter.manager.TestApplication
import eu.darken.porter.manager.utils.SettingsHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [34])
class WirelessDebuggingOffTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val writes = mutableListOf<Pair<String, Int>>()

    /** Records the call and lands the write the way production does. */
    private val recordingWrite: (ContentResolver, String, Int) -> Boolean = { cr, name, value ->
        writes += name to value
        SettingsHelper.tryPutGlobalInt(cr, name, value)
    }

    private val neverConnects: suspend () -> Unit = { awaitCancellation() }

    @Before fun setUp() {
        application.createDeviceProtectedStorageContext()
            .getSharedPreferences(PorterSettings.NAME, Context.MODE_PRIVATE).edit().clear().commit()
        PorterSettings.resetForTest()
        PorterSettings.initialize(application)
        Settings.Global.putInt(application.contentResolver, ADB_WIFI_ENABLED, 1)
        shadowOf(application).grantPermissions(WRITE_SECURE_SETTINGS)
    }

    private fun adbWifi() = Settings.Global.getInt(application.contentResolver, ADB_WIFI_ENABLED, -1)

    @Test fun theSettingIsOffByDefault() {
        assertFalse(PorterSettings.wirelessDebuggingOff)
    }

    @Test fun theSwitchWriteIsStored() {
        PorterSettings.setWirelessDebuggingOff(true)
        assertTrue(PorterSettings.wirelessDebuggingOff)
        assertTrue(PorterSettings.preferences.getBoolean(PorterSettings.Keys.KEY_WIRELESS_DEBUGGING_OFF, false))

        PorterSettings.setWirelessDebuggingOff(false)
        assertFalse(PorterSettings.wirelessDebuggingOff)
    }

    @Test fun aStartWithTheSettingOffLeavesWirelessDebuggingOn() = runTest {
        turnOffWirelessDebuggingAfterStart(application, overWireless = true, write = recordingWrite, awaitConnection = neverConnects)
        assertEquals(1, adbWifi())
        assertEquals(emptyList<Pair<String, Int>>(), writes)
    }

    @Test fun aStartOverATcpPortLeavesWirelessDebuggingOn() = runTest {
        PorterSettings.setWirelessDebuggingOff(true)
        turnOffWirelessDebuggingAfterStart(application, overWireless = false, write = recordingWrite, awaitConnection = neverConnects)
        assertEquals(1, adbWifi())
        assertEquals(emptyList<Pair<String, Int>>(), writes)
    }

    @Test fun aWirelessStartWithTheGrantTurnsWirelessDebuggingOffOnce() = runTest {
        PorterSettings.setWirelessDebuggingOff(true)
        turnOffWirelessDebuggingAfterStart(application, overWireless = true, write = recordingWrite, awaitConnection = neverConnects)
        assertEquals(0, adbWifi())
        assertEquals(listOf(ADB_WIFI_ENABLED to 0), writes)
    }

    @Test fun aDeniedWriteDoesNotThrow() = runTest {
        PorterSettings.setWirelessDebuggingOff(true)
        turnOffWirelessDebuggingAfterStart(application, overWireless = true, write = { _, _, _ -> false }, awaitConnection = neverConnects)
        assertEquals(1, adbWifi())
    }

    @Test fun aFailingWriteDoesNotThrow() = runTest {
        PorterSettings.setWirelessDebuggingOff(true)
        turnOffWirelessDebuggingAfterStart(application, overWireless = true,
            write = { _, _, _ -> throw IllegalStateException("provider gone") }, awaitConnection = neverConnects)
        assertEquals(1, adbWifi())
    }

    @Test fun cancellationDuringTheWritePropagates() = runTest {
        PorterSettings.setWirelessDebuggingOff(true)
        try {
            turnOffWirelessDebuggingAfterStart(application, overWireless = true,
                write = { _, _, _ -> throw CancellationException("stopped") }, awaitConnection = neverConnects)
            fail("cancellation was swallowed")
        } catch (e: CancellationException) {
            assertEquals("stopped", e.message)
        }
    }

    /** The server grants the permission when the manager attaches, which can follow the running state. */
    @Test fun aGrantThatArrivesWithTheConnectionIsWaitedFor() = runTest {
        PorterSettings.setWirelessDebuggingOff(true)
        shadowOf(application).denyPermissions(WRITE_SECURE_SETTINGS)
        var waited = 0
        turnOffWirelessDebuggingAfterStart(application, overWireless = true, write = recordingWrite, awaitConnection = {
            waited++
            shadowOf(application).grantPermissions(WRITE_SECURE_SETTINGS)
        })
        assertEquals(1, waited)
        assertEquals(0, adbWifi())
        assertEquals(listOf(ADB_WIFI_ENABLED to 0), writes)
    }

    @Test fun aConnectionWithoutTheGrantSkipsTheWrite() = runTest {
        PorterSettings.setWirelessDebuggingOff(true)
        shadowOf(application).denyPermissions(WRITE_SECURE_SETTINGS)
        turnOffWirelessDebuggingAfterStart(application, overWireless = true, write = recordingWrite, awaitConnection = {})
        assertEquals(1, adbWifi())
        assertEquals(emptyList<Pair<String, Int>>(), writes)
    }

    @Test fun aConnectionThatNeverArrivesEndsTheWaitAtTheBound() = runTest {
        PorterSettings.setWirelessDebuggingOff(true)
        shadowOf(application).denyPermissions(WRITE_SECURE_SETTINGS)
        turnOffWirelessDebuggingAfterStart(application, overWireless = true, write = recordingWrite, awaitConnection = neverConnects)
        assertEquals(GRANT_WAIT_MS, testScheduler.currentTime)
        assertEquals(1, adbWifi())
        assertEquals(emptyList<Pair<String, Int>>(), writes)
    }
}
