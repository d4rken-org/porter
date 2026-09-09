package moe.shizuku.manager.adb

import android.app.Notification
import android.app.NotificationManager
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import moe.shizuku.manager.R
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import java.net.InetAddress
import java.net.ServerSocket

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AdbPairingServiceTest {
    private val dispatcher = StandardTestDispatcher()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var controller: ServiceController<AdbPairingService>
    private val service get() = controller.get()
    private val notifications = shadowOf(context.getSystemService(NotificationManager::class.java))
    private val nsd = shadowOf(context.getSystemService(NsdManager::class.java))
    private val notification get() = notifications.getNotification(AdbPairingService.NOTIFICATION_ID)
    private fun idle() { shadowOf(Looper.getMainLooper()).idle(); dispatcher.scheduler.runCurrent() }
    private fun title() = notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        controller = Robolectric.buildService(AdbPairingService::class.java).create()
    }
    @After fun cleanup() { controller.destroy(); Dispatchers.resetMain() }
    private fun start() { service.onStartCommand(AdbPairingService.startIntent(context), 0, 1); idle() }
    private fun reply(code: String) {
        val intent = Intent(context, AdbPairingService::class.java).setAction("reply")
            .putExtra("pairing_host", "127.0.0.1").putExtra("paring_code", 37123)
        RemoteInput.addResultsToIntent(arrayOf(RemoteInput.Builder("paring_code").build()), intent,
            Bundle().apply { putCharSequence("paring_code", code) })
        service.onStartCommand(intent, 0, 2)
        idle()
    }

    private fun await(check: () -> Boolean) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (!check() && System.nanoTime() < deadline) { idle(); Thread.sleep(10) }
        assertTrue("Callback did not arrive", check())
    }

    @Test fun wrongCodePublishesFailureAndFreshRetryAcceptsNewCode() {
        val codes = mutableListOf<String>()
        service.pair = { _, _, code ->
            codes += code
            if (codes.size == 1) throw AdbInvalidPairingCodeException()
        }
        start()
        reply("111111")
        assertEquals(context.getString(R.string.notification_adb_pairing_failed_title), title())
        assertEquals(context.getString(R.string.paring_code_is_wrong), notification.extras.getString(Notification.EXTRA_TEXT))
        assertTrue(shadowOf(notification.actions.single().actionIntent).isForegroundService)
        start()
        assertEquals(context.getString(R.string.notification_adb_pairing_searching_for_service_title), title())
        reply("222222")
        assertEquals(context.getString(R.string.notification_adb_pairing_succeed_title), title())
        assertEquals(listOf("111111", "222222"), codes)
    }

    @Test fun lostEndpointCancelsOldInputAndNewDiscoveryCreatesFreshReply() {
        start()
        val listener = nsd.getDiscoveryListeners(AdbMdns.TLS_PAIRING)!!.single()
        listener.onDiscoveryStarted(AdbMdns.TLS_PAIRING)
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { socket ->
            val info = NsdServiceInfo().apply {
                serviceName = "adb-test"; serviceType = AdbMdns.TLS_PAIRING; host = InetAddress.getLoopbackAddress(); port = socket.localPort
            }
            listener.onServiceFound(info); idle()
            nsd.getResolveListeners(info)!!.last().onServiceResolved(info); idle()
            await { notification.actions.first().remoteInputs != null }
            val oldReply = notification.actions.first().actionIntent
            assertNotNull(notification.actions.first().remoteInputs)
            listener.onServiceLost(info); idle()
            assertTrue(shadowOf(oldReply).isCanceled)
            assertTrue(notification.actions.all { it.remoteInputs == null })
            listener.onServiceFound(info); idle()
            nsd.getResolveListeners(info)!!.last().onServiceResolved(info); idle()
            await { notification.actions.first().remoteInputs != null }
            assertFalse(shadowOf(notification.actions.first().actionIntent).isCanceled)
        }
    }

    @Test fun restartPreventsAnOldAttemptFromReplacingFreshSearch() {
        val result = CompletableDeferred<Unit>()
        service.pair = { _, _, _ -> result.await() }
        start(); reply("123456")
        assertEquals(context.getString(R.string.notification_adb_pairing_working_title), title())
        start()
        result.complete(Unit); idle()
        assertEquals(context.getString(R.string.notification_adb_pairing_searching_for_service_title), title())
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test fun timeoutAndKeyErrorsRemainRetryable() {
        start()
        service.onTimeout(1); idle()
        assertEquals(context.getString(R.string.notification_adb_pairing_failed_title), title())
        assertEquals(context.getString(R.string.notification_adb_pairing_retry), notification.actions.single().title)
        start()
        service.pair = { _, _, _ -> throw AdbKeyException(IllegalStateException("key unavailable")) }
        reply("123456")
        assertEquals(context.getString(R.string.adb_error_key_store), notification.extras.getString(Notification.EXTRA_TEXT))
    }
}
