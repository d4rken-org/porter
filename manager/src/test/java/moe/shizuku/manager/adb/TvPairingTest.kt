package moe.shizuku.manager.adb

import android.os.Looper
import android.os.LocaleList
import android.app.LocaleManager
import android.widget.LinearLayout
import android.widget.TextView
import android.view.View
import android.view.WindowManager
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
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowWindowManagerImpl
import java.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TvPairingTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var controller: ServiceController<AdbPairingAccessibilityService>
    private val service get() = controller.get()
    private val store get() = TvPairingResultStore(ShizukuSettings.getPreferences())
    private val views get() = Shadow.extract<ShadowWindowManagerImpl>(service.getSystemService(WindowManager::class.java)).views
    private fun idle() { shadowOf(Looper.getMainLooper()).idle(); dispatcher.scheduler.runCurrent() }
    private fun tree(vararg texts: String) = AccessibilityNodeInfo.obtain().apply {
        packageName = TV_SETTINGS_PACKAGE
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

    @Test fun unbindingDuringPairingCancelsWorkAndDoesNotReopenPorter() {
        val result = CompletableDeferred<Unit>()
        service.pair = { _, _, _ -> result.await() }
        service.startPairing()
        shadowOf(service).nextStartedActivity
        credentials()
        assertEquals(1, views.size)
        service.onUnbind(null)
        assertTrue(views.isEmpty())
        result.complete(Unit)
        idle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(65))
        assertNull(store.read())
        assertNull(shadowOf(service).nextStartedActivity)
    }

    @Test fun blankPairingCodeWindowShowsProgressBeforeCredentialsAndStillTimesOut() {
        var attempts = 0
        service.pair = { _, _, _ -> attempts++ }
        service.startPairing()
        val root = tree().apply { viewIdResourceName = "$TV_SETTINGS_PACKAGE:id/pairing_code" }
        shadowOf(service).setRootInActiveWindow(root)
        service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))
        idle()
        assertEquals(1, views.size)
        assertEquals(0, attempts)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60))
        assertFalse(store.read()!!.success)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertTrue(views.isEmpty())
    }

    @Test fun resultRemainsVisibleBeforeHandoffAndDuplicateEventsDoNotPairAgain() {
        var attempts = 0
        service.pair = { _, _, _ -> attempts++ }
        service.startPairing()
        shadowOf(service).nextStartedActivity
        val root = tree().apply { viewIdResourceName = "$TV_SETTINGS_PACKAGE:id/pairing_code" }
        shadowOf(service).setRootInActiveWindow(root)
        service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))
        credentials()
        credentials()
        assertEquals(1, attempts)
        assertEquals(1, views.size)
        val params = views.single().layoutParams as WindowManager.LayoutParams
        assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, params.type)
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2400))
        assertNull(shadowOf(service).nextStartedActivity)
        assertEquals(1, views.size)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertNotNull(shadowOf(service).nextStartedActivity)
        assertTrue(views.isEmpty())
    }

    @Test fun unbindingDuringResultRetentionRemovesOverlayAndCancelsHandoff() {
        service.pair = { _, _, _ -> }
        service.startPairing()
        shadowOf(service).nextStartedActivity
        credentials()
        assertEquals(1, views.size)
        service.onUnbind(null)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertTrue(views.isEmpty())
        assertNull(shadowOf(service).nextStartedActivity)
        assertTrue(store.read()!!.success)
    }

    @Test fun leavingSettingsHidesOverlayAndCompletionDoesNotWaitForHiddenPanel() {
        val result = CompletableDeferred<Unit>()
        service.pair = { _, _, _ -> result.await() }
        service.startPairing()
        shadowOf(service).nextStartedActivity
        credentials()
        shadowOf(service).setRootInActiveWindow(tree().apply { packageName = "com.example.launcher" })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(View.GONE, views.single().visibility)
        result.complete(Unit); idle()
        assertTrue(views.isEmpty())
        assertNotNull(shadowOf(service).nextStartedActivity)
    }

    @Test fun destroyingWhilePairingRemovesOverlay() {
        service.pair = { _, _, _ -> CompletableDeferred<Unit>().await() }
        service.startPairing()
        credentials()
        assertEquals(1, views.size)
        service.onDestroy()
        assertTrue(views.isEmpty())
    }

    @Test fun serviceUsesTheSelectedAppLanguageForOverlayAndPersistedResult() {
        service.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("de")
        service.pair = { _, _, _ -> }
        service.startPairing()
        credentials()
        val title = (views.single() as LinearLayout).getChildAt(0) as TextView
        assertEquals("Porter-Kopplung", title.text.toString())
        assertEquals("Du kannst den Porter-Dienst jetzt starten.", store.read()!!.message)
    }

}
