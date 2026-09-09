package moe.shizuku.manager.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.net.InetAddress
import java.net.ServerSocket

@RunWith(RobolectricTestRunner::class)
class AdbMdnsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val nsd = shadowOf(context.getSystemService(NsdManager::class.java))
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun await(check: () -> Boolean) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (!check() && System.nanoTime() < deadline) { idle(); Thread.sleep(10) }
        assertTrue("Callback did not arrive", check())
    }

    @Test fun stopBeforeRegistrationAndRestartCannotReviveOldListener() {
        val results = mutableListOf<Pair<String, Int>>()
        val mdns = AdbMdns(context, AdbMdns.TLS_PAIRING) { results += it }
        mdns.start()
        val old = nsd.getDiscoveryListeners(AdbMdns.TLS_PAIRING)!!.single()
        mdns.stop()
        mdns.start()
        val current = nsd.getDiscoveryListeners(AdbMdns.TLS_PAIRING)!!.last()
        old.onDiscoveryStarted(AdbMdns.TLS_PAIRING)
        idle()
        assertNull(nsd.getDiscoveryListenerServiceType(old))
        assertEquals(AdbMdns.TLS_PAIRING, nsd.getDiscoveryListenerServiceType(current))
        old.onDiscoveryStopped(AdbMdns.TLS_PAIRING)
        old.onServiceFound(NsdServiceInfo().apply { serviceName = "old" })
        idle()
        assertTrue(results.isEmpty())
        assertEquals(AdbMdns.TLS_PAIRING, nsd.getDiscoveryListenerServiceType(current))
        mdns.stop()
    }

    @Test fun lateResolutionOfLostServiceIsIgnoredForPairingAndStartup() {
        for (type in listOf(AdbMdns.TLS_PAIRING, AdbMdns.TLS_CONNECT)) {
            val results = mutableListOf<Pair<String, Int>>()
            val mdns = AdbMdns(context, type) { results += it }
            mdns.start()
            val listener = nsd.getDiscoveryListeners(type)!!.single()
            listener.onDiscoveryStarted(type)
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { socket ->
                val info = NsdServiceInfo().apply {
                    serviceName = "adb-test"
                    serviceType = type
                    host = InetAddress.getLoopbackAddress()
                    port = socket.localPort
                }
                listener.onServiceFound(info)
                idle()
                val resolution = nsd.getResolveListeners(info)!!.single()
                listener.onServiceLost(info)
                idle()
                resolution.onServiceResolved(info)
                idle()
                assertTrue(results.isEmpty())
                listener.onServiceFound(info)
                idle()
                nsd.getResolveListeners(info)!!.last().onServiceResolved(info)
                idle()
                await { results.isNotEmpty() }
                assertEquals(listOf("127.0.0.1" to socket.localPort), results)
                listener.onServiceLost(info)
                idle()
                assertEquals("" to -1, results.last())
            }
            mdns.stop()
        }
    }

    @Test fun discoveryFailureAllowsAnotherStart() {
        val mdns = AdbMdns(context, AdbMdns.TLS_PAIRING) {}
        mdns.start()
        val old = nsd.getDiscoveryListeners(AdbMdns.TLS_PAIRING)!!.single()
        old.onStartDiscoveryFailed(AdbMdns.TLS_PAIRING, NsdManager.FAILURE_INTERNAL_ERROR)
        idle()
        mdns.start()
        assertNotSame(old, nsd.getDiscoveryListeners(AdbMdns.TLS_PAIRING)!!.last())
        mdns.stop()
    }
}
