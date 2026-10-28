package eu.darken.porter.manager.adb

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.app.Application
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import eu.darken.porter.manager.PorterSettings
import eu.darken.porter.manager.TestApplication
import java.net.ConnectException
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Both ADB start paths share this step; only a start over wireless debugging may turn it off. */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [34])
class StartOverAdbTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val calls = mutableListOf<String>()
    private val turnOffs = mutableListOf<Boolean>()

    private val recordingStart: suspend (Context, Int, Boolean, ((String) -> Unit)?) -> Unit = { _, port, tls, _ ->
        calls += "start $port $tls"
    }
    private val recordingAwait: suspend (((String) -> Unit)?) -> Unit = { calls += "await" }
    private val recordingTurnOff: suspend (Context, Boolean) -> Unit = { _, wireless ->
        calls += "turnOff"
        turnOffs += wireless
    }

    @Before fun setUp() {
        application.createDeviceProtectedStorageContext()
            .getSharedPreferences(PorterSettings.NAME, Context.MODE_PRIVATE).edit().clear().commit()
        PorterSettings.resetForTest()
        PorterSettings.initialize(application)
        Settings.Global.putInt(application.contentResolver, ADB_WIFI_ENABLED, 1)
        shadowOf(application).grantPermissions(WRITE_SECURE_SETTINGS)
    }

    private fun adbWifi() = Settings.Global.getInt(application.contentResolver, ADB_WIFI_ENABLED, -1)

    @Test fun aTcpStartTurnsOffNotOverWireless() = runTest {
        startOverAdb(application, 5555, requireTls = false, start = recordingStart, awaitService = recordingAwait, turnOff = recordingTurnOff)
        assertEquals(listOf(false), turnOffs)
    }

    @Test fun aTlsStartTurnsOffOverWireless() = runTest {
        startOverAdb(application, 37123, requireTls = true, start = recordingStart, awaitService = recordingAwait, turnOff = recordingTurnOff)
        assertEquals(listOf(true), turnOffs)
    }

    @Test fun theStepsRunInOrder() = runTest {
        startOverAdb(application, 37123, requireTls = true, start = recordingStart, awaitService = recordingAwait, turnOff = recordingTurnOff)
        assertEquals(listOf("start 37123 true", "await", "turnOff"), calls)
    }

    @Test fun aFailedStartPropagatesWithoutTurningOff() = runTest {
        val failure = ConnectException("refused")
        try {
            startOverAdb(application, 37123, requireTls = true, start = { _, _, _, _ -> throw failure },
                awaitService = recordingAwait, turnOff = recordingTurnOff)
            fail("the start failure was swallowed")
        } catch (e: ConnectException) {
            assertSame(failure, e)
        }
        assertEquals(emptyList<String>(), calls)
    }

    @Test fun aFailedWaitPropagatesWithoutTurningOff() = runTest {
        val failure = TimeoutException("no binder")
        try {
            startOverAdb(application, 37123, requireTls = true, start = recordingStart,
                awaitService = { throw failure }, turnOff = recordingTurnOff)
            fail("the wait failure was swallowed")
        } catch (e: TimeoutException) {
            assertSame(failure, e)
        }
        assertEquals(listOf("start 37123 true"), calls)
    }

    @Test fun theDefaultTurnOffActsOnlyOnAWirelessStart() = runTest {
        PorterSettings.setWirelessDebuggingOff(true)

        startOverAdb(application, 5555, requireTls = false, start = recordingStart, awaitService = recordingAwait)
        assertEquals(1, adbWifi())

        startOverAdb(application, 37123, requireTls = true, start = recordingStart, awaitService = recordingAwait)
        assertEquals(0, adbWifi())
    }
}
