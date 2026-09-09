package moe.shizuku.manager.adb

import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import java.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TvPairingTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var controller: ServiceController<AdbPairingAccessibilityService>
    private val service get() = controller.get()
    private val store get() = TvPairingResultStore(ShizukuSettings.getPreferences())
    private fun idle() { shadowOf(Looper.getMainLooper()).idle(); dispatcher.scheduler.runCurrent() }
    private fun tree(vararg texts: String) = AccessibilityNodeInfo.obtain().apply {
        texts.forEach { value -> shadowOf(this).addChild(AccessibilityNodeInfo.obtain().apply { text = value }) }
    }
    private fun credentials() {
        shadowOf(service).setRootInActiveWindow(tree("192.168.1.20:37123", "123456"))
        service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
        idle()
    }

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        controller = Robolectric.buildService(AdbPairingAccessibilityService::class.java).create()
        store.clear()
    }
    @After fun cleanup() { controller.destroy(); store.clear(); Dispatchers.resetMain() }

    @Test fun timeoutPersistsRecoveryInstructionsUntilAcknowledged() {
        service.startPairing()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60))
        val result = store.read()!!
        assertFalse(result.success)
        assertEquals(service.getString(R.string.porter_pairing_search_timeout), result.message)
        assertEquals(result, TvPairingResultStore(ShizukuSettings.getPreferences()).read())
        assertNotNull(shadowOf(service).nextStartedActivity)
        store.clear()
        assertNull(store.read())
    }

    @Test fun detectingCredentialsCancelsSearchTimeoutAndPreservesSuccess() {
        val result = CompletableDeferred<Unit>()
        var received: PairingCredentials? = null
        service.pair = { host, port, code -> received = PairingCredentials(host, port, code); result.await() }
        service.startPairing()
        credentials()
        assertEquals(PairingCredentials("192.168.1.20", 37123, "123456"), received)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(65))
        assertNull(store.read())
        result.complete(Unit); idle()
        assertTrue(store.read()!!.success)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(65))
        assertTrue(store.read()!!.success)
    }

    @Test fun keyFailureIsReportedAndNextSessionAcceptsFreshCredentials() {
        service.pair = { _, _, _ -> throw AdbKeyException(IllegalStateException("unavailable")) }
        service.startPairing(); credentials()
        assertFalse(store.read()!!.success)
        assertEquals(service.getString(R.string.adb_error_key_store), store.read()!!.message)
        service.onUnbind(null)
        service.startPairing()
        assertNull(store.read())
        service.pair = { _, _, _ -> }
        credentials()
        assertTrue(store.read()!!.success)
    }

    @Test fun partialOrInvalidSnapshotsCannotPairWithOldCredentials() {
        assertNull(readPairingCredentials(tree("123456")))
        assertNull(readPairingCredentials(tree("192.168.1.20:37123")))
        assertNull(readPairingCredentials(tree("192.168.1.20:99999", "123456")))
        assertEquals(PairingCredentials("192.168.1.20", 37123, "123456"),
            readPairingCredentials(tree("192.168.1.20:37123", "123456")))
    }
}
