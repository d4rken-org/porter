package eu.darken.porter.manager.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Looper
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog
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

    private fun entries(prefix: String, listener: String? = null) = ShadowLog.getLogsForTag(AdbMdns.TAG)
        .filter { it.msg.startsWith(prefix) && (listener == null || it.field("listener") == listener) }
    private fun ShadowLog.LogItem.field(key: String) = Regex("\\b$key=(\\S+)").find(msg)?.groupValues?.get(1)

    private fun discover(type: String, results: MutableList<Pair<String, Int>>): Pair<AdbMdns, NsdManager.DiscoveryListener> {
        val mdns = AdbMdns(context, type) { results += it }
        mdns.start()
        val listener = nsd.getDiscoveryListeners(type)!!.last()
        listener.onDiscoveryStarted(type)
        idle()
        return mdns to listener
    }

    private fun service(
        port: Int,
        host: InetAddress? = InetAddress.getLoopbackAddress(),
        type: String = AdbMdns.TLS_CONNECT,
    ) = NsdServiceInfo().apply {
        serviceName = SERVICE_NAME
        serviceType = type
        if (host != null) this.host = host
        this.port = port
    }

    private fun resolveOnce(info: NsdServiceInfo, listener: NsdManager.DiscoveryListener) {
        listener.onServiceFound(info)
        idle()
        nsd.getResolveListeners(info)!!.single().onServiceResolved(info)
    }

    private fun assertResolves(expected: Int, info: NsdServiceInfo, listener: String? = null) {
        assertEquals(expected, nsd.getResolveListeners(info)!!.size)
        assertEquals(expected, entries("Resolve issued:", listener).size)
    }

    @After fun noRawServiceNameIsLogged() {
        for (item in ShadowLog.getLogsForTag(AdbMdns.TAG)) {
            assertFalse(item.msg, item.msg.contains(SERIAL))
            assertFalse(item.msg, item.msg.contains("adb-test"))
        }
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
                assertEquals("lost", entries("Resolve outcome:").last().field("result"))
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

    @Test fun duplicateSightingsResolveOnceForPairingAndStartup() {
        for (type in listOf(AdbMdns.TLS_PAIRING, AdbMdns.TLS_CONNECT)) {
            val results = mutableListOf<Pair<String, Int>>()
            val (mdns, listener) = discover(type, results)
            val id = entries("Discovery start: ").last().field("listener")
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { socket ->
                val first = service(socket.localPort, type = type)
                listener.onServiceFound(first)
                idle()
                listener.onServiceFound(service(socket.localPort, type = type))
                idle()
                assertResolves(1, first, id)
                val skipped = entries("Resolve skipped:", id).single()
                assertEquals("S1", skipped.field("service"))
                assertEquals("pending", skipped.field("reason"))
                assertEquals(Log.INFO, skipped.type)
                assertEquals(listOf("1", "2"), entries("Service found:", id).map { it.field("seen") })
                nsd.getResolveListeners(first)!!.single().onServiceResolved(first)
                await { results.isNotEmpty() }
                assertEquals(listOf("127.0.0.1" to socket.localPort), results)
            }
            assertEquals("accepted", entries("Resolve outcome:", id).single().field("result"))
            mdns.stop()
        }
    }

    @Test fun resolutionOfLostAndRefoundEntryIsLoggedAsReplaced() {
        val results = mutableListOf<Pair<String, Int>>()
        val (mdns, listener) = discover(AdbMdns.TLS_CONNECT, results)
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { socket ->
            val first = service(socket.localPort)
            val second = service(socket.localPort)
            listener.onServiceFound(first)
            idle()
            val resolution = nsd.getResolveListeners(first)!!.single()
            listener.onServiceLost(first)
            idle()
            listener.onServiceFound(second)
            idle()
            assertResolves(2, first)
            resolution.onServiceResolved(first)
            idle()
            assertTrue(results.isEmpty())
            assertEquals("replaced", entries("Resolve outcome:").single().field("result"))
            nsd.getResolveListeners(first)!!.last().onServiceResolved(second)
            await { results.isNotEmpty() }
            assertEquals(listOf("127.0.0.1" to socket.localPort), results)
        }
        val found = entries("Service found:")
        assertEquals(listOf("S1", "S1"), found.map { it.field("service") })
        assertEquals(listOf("1", "2"), found.map { it.field("seen") })
        assertTrue(found.all { it.type == Log.INFO })
        val outcomes = entries("Resolve outcome:")
        assertEquals(listOf("replaced", "accepted"), outcomes.map { it.field("result") })
        assertEquals(listOf("R1", "R2"), outcomes.map { it.field("resolve") })
        assertTrue(outcomes.all { it.type == Log.INFO })
        mdns.stop()
    }

    @Test fun sightingAfterAcceptanceIssuesNoResolve() {
        val results = mutableListOf<Pair<String, Int>>()
        val (mdns, listener) = discover(AdbMdns.TLS_CONNECT, results)
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { socket ->
            val first = service(socket.localPort)
            resolveOnce(first, listener)
            await { results.isNotEmpty() }
            listener.onServiceFound(service(socket.localPort))
            idle()
            assertResolves(1, first)
            assertEquals("accepted", entries("Resolve skipped:").single().field("reason"))
            assertEquals(listOf("127.0.0.1" to socket.localPort), results)
        }
        mdns.stop()
    }

    @Test fun failedResolveLetsTheNextSightingResolve() {
        val results = mutableListOf<Pair<String, Int>>()
        val (mdns, listener) = discover(AdbMdns.TLS_CONNECT, results)
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { socket ->
            val first = service(socket.localPort)
            listener.onServiceFound(first)
            idle()
            nsd.getResolveListeners(first)!!.single().onResolveFailed(first, NsdManager.FAILURE_INTERNAL_ERROR)
            idle()
            val second = service(socket.localPort)
            listener.onServiceFound(second)
            idle()
            assertResolves(2, first)
            assertTrue(entries("Resolve skipped:").isEmpty())
            nsd.getResolveListeners(first)!!.last().onServiceResolved(second)
            await { results.isNotEmpty() }
            assertEquals(listOf("127.0.0.1" to socket.localPort), results)
        }
        assertEquals("accepted", entries("Resolve outcome:").single().field("result"))
        mdns.stop()
    }

    @Test fun staleResolveFailureKeepsTheNewerEntry() {
        val (mdns, listener) = discover(AdbMdns.TLS_CONNECT, mutableListOf())
        val first = service(5555)
        listener.onServiceFound(first)
        idle()
        val resolution = nsd.getResolveListeners(first)!!.single()
        listener.onServiceLost(first)
        idle()
        listener.onServiceFound(service(5555))
        idle()
        resolution.onResolveFailed(first, NsdManager.FAILURE_INTERNAL_ERROR)
        idle()
        listener.onServiceFound(service(5555))
        idle()
        assertResolves(2, first)
        assertEquals("pending", entries("Resolve skipped:").single().field("reason"))
        mdns.stop()
    }

    @Test fun staleRejectionAfterTheProbeKeepsTheNewerEntry() {
        val results = mutableListOf<Pair<String, Int>>()
        val (mdns, listener) = discover(AdbMdns.TLS_CONNECT, results)
        val closed = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { socket ->
            val first = service(closed)
            val second = service(socket.localPort)
            listener.onServiceFound(first)
            idle()
            nsd.getResolveListeners(first)!!.single().onServiceResolved(first)
            listener.onServiceLost(first)
            listener.onServiceFound(second)
            await { entries("Resolve outcome:").isNotEmpty() }
            assertEquals("port-closed", entries("Resolve outcome:").single().field("result"))
            listener.onServiceFound(service(socket.localPort))
            idle()
            assertResolves(2, first)
            assertEquals("pending", entries("Resolve skipped:").single().field("reason"))
            nsd.getResolveListeners(first)!!.last().onServiceResolved(second)
            await { results.isNotEmpty() }
            assertEquals(listOf("127.0.0.1" to socket.localPort), results)
        }
        assertEquals(listOf("port-closed", "accepted"), entries("Resolve outcome:").map { it.field("result") })
        mdns.stop()
    }

    @Test fun nonLocalHostIsLoggedAsNoInterface() {
        val results = mutableListOf<Pair<String, Int>>()
        val (mdns, listener) = discover(AdbMdns.TLS_CONNECT, results)
        val info = service(5555, InetAddress.getByName("203.0.113.1"))
        resolveOnce(info, listener)
        await { entries("Resolve outcome:").isNotEmpty() }
        assertEquals("no-interface", entries("Resolve outcome:").single().field("result"))
        assertEquals("ipv4-other", entries("Service resolved:").single().field("host"))
        assertTrue(results.isEmpty())
        listener.onServiceFound(service(5555, InetAddress.getByName("203.0.113.1")))
        idle()
        assertResolves(2, info)
        mdns.stop()
    }

    @Test fun unopenedLoopbackPortIsLoggedAsPortClosed() {
        val results = mutableListOf<Pair<String, Int>>()
        val (mdns, listener) = discover(AdbMdns.TLS_CONNECT, results)
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val info = service(port)
        resolveOnce(info, listener)
        await { entries("Resolve outcome:").isNotEmpty() }
        assertEquals("port-closed", entries("Resolve outcome:").single().field("result"))
        assertTrue(results.isEmpty())
        listener.onServiceFound(service(port))
        idle()
        assertResolves(2, info)
        mdns.stop()
    }

    @Test fun resolutionWithoutHostIsLoggedAsNoHost() {
        val results = mutableListOf<Pair<String, Int>>()
        val (mdns, listener) = discover(AdbMdns.TLS_CONNECT, results)
        val info = service(5555, host = null)
        resolveOnce(info, listener)
        idle()
        assertEquals("no-host", entries("Resolve outcome:").single().field("result"))
        assertEquals("none", entries("Service resolved:").single().field("host"))
        assertTrue(results.isEmpty())
        listener.onServiceFound(service(5555, host = null))
        idle()
        assertResolves(2, info)
        mdns.stop()
    }

    @Test fun resolutionAfterStopIsLoggedAsInactive() {
        val results = mutableListOf<Pair<String, Int>>()
        val (mdns, listener) = discover(AdbMdns.TLS_CONNECT, results)
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { socket ->
            val info = service(socket.localPort)
            listener.onServiceFound(info)
            idle()
            val resolution = nsd.getResolveListeners(info)!!.single()
            mdns.stop()
            idle()
            resolution.onServiceResolved(info)
            idle()
        }
        assertEquals("inactive", entries("Resolve outcome:").single().field("result"))
        assertNotNull(entries("Service resolved:").single().field("resolve"))
        assertTrue(results.isEmpty())
    }

    @Test fun acceptedResolutionIsLoggedWithHostKind() {
        val results = mutableListOf<Pair<String, Int>>()
        val (mdns, listener) = discover(AdbMdns.TLS_CONNECT, results)
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { socket ->
            resolveOnce(service(socket.localPort), listener)
            await { results.isNotEmpty() }
            assertEquals(listOf("127.0.0.1" to socket.localPort), results)
            val resolved = entries("Service resolved:").single()
            assertEquals("ipv4-loopback", resolved.field("host"))
            assertEquals(socket.localPort.toString(), resolved.field("port"))
            assertEquals(Log.INFO, resolved.type)
        }
        val outcome = entries("Resolve outcome:").single()
        assertEquals("accepted", outcome.field("result"))
        assertEquals(Log.INFO, outcome.type)
        assertEquals(1, entries("Service accepted: type=${AdbMdns.TLS_CONNECT} port=").size)
        mdns.stop()
    }

    @Test fun resolveFailureCarriesTheResolveIdentity() {
        val (mdns, listener) = discover(AdbMdns.TLS_CONNECT, mutableListOf())
        val info = service(5555)
        listener.onServiceFound(info)
        idle()
        nsd.getResolveListeners(info)!!.single().onResolveFailed(info, NsdManager.FAILURE_ALREADY_ACTIVE)
        val issued = entries("Resolve issued:").single()
        val failed = entries("Resolving service failed: 3 ").single()
        assertEquals(Log.INFO, issued.type)
        assertEquals(Log.WARN, failed.type)
        for (key in listOf("listener", "client", "service", "resolve")) {
            assertNotNull(key, failed.field(key))
            assertEquals(key, issued.field(key), failed.field(key))
        }
        mdns.stop()
    }

    @Test fun resolveSubmissionFailureCarriesTheResolveIdentity() {
        val (mdns, listener) = discover(AdbMdns.TLS_CONNECT, mutableListOf())
        listener.onServiceFound(NsdServiceInfo().apply { serviceName = SERVICE_NAME })
        idle()
        val issued = entries("Resolve issued:").single()
        val failed = entries("Cannot resolve service: ").single()
        assertEquals(Log.WARN, failed.type)
        assertNotNull(failed.throwable)
        assertEquals("R1", failed.field("resolve"))
        assertEquals(issued.field("resolve"), failed.field("resolve"))
        assertEquals(issued.field("service"), failed.field("service"))
        val valid = service(5555)
        listener.onServiceFound(valid)
        idle()
        assertEquals(1, nsd.getResolveListeners(valid)!!.size)
        assertEquals(2, entries("Resolve issued:").size)
        assertTrue(entries("Resolve skipped:").isEmpty())
        mdns.stop()
    }

    @Test fun probeErrorIsLoggedAndLetsTheNextSightingResolve() {
        val results = mutableListOf<Pair<String, Int>>()
        val (mdns, listener) = discover(AdbMdns.TLS_CONNECT, results)
        val info = service(70000)
        resolveOnce(info, listener)
        await { entries("Resolve outcome:").isNotEmpty() }
        assertEquals("probe-error", entries("Resolve outcome:").single().field("result"))
        val issued = entries("Resolve issued:").single()
        val failed = entries("Probe failed:").single()
        assertEquals(Log.WARN, failed.type)
        assertTrue(failed.throwable is IllegalArgumentException)
        for (key in listOf("listener", "service", "resolve")) {
            assertNotNull(key, failed.field(key))
            assertEquals(key, issued.field(key), failed.field(key))
        }
        assertTrue(results.isEmpty())
        listener.onServiceFound(service(70000))
        idle()
        assertResolves(2, info)
        mdns.stop()
    }

    @Test fun instancesOnOneContextShareTheClientButNotTheListener() {
        val (pairing, pairingListener) = discover(AdbMdns.TLS_PAIRING, mutableListOf())
        val (connect, _) = discover(AdbMdns.TLS_CONNECT, mutableListOf())
        val starts = entries("Discovery start: ")
        assertEquals(2, starts.size)
        assertNotEquals(starts[0].field("listener"), starts[1].field("listener"))
        val client = Integer.toHexString(System.identityHashCode(context.getSystemService(NsdManager::class.java)))
        assertEquals(listOf(client, client), starts.map { it.field("client") })
        pairingListener.onStartDiscoveryFailed(AdbMdns.TLS_PAIRING, NsdManager.FAILURE_INTERNAL_ERROR)
        idle()
        val failed = entries("Discovery failed: ${NsdManager.FAILURE_INTERNAL_ERROR} ").single()
        assertEquals(Log.WARN, failed.type)
        assertEquals(starts[0].field("listener"), failed.field("listener"))
        assertEquals("true", failed.field("active"))
        pairing.stop()
        connect.stop()
    }

    @Test fun lifecycleLinesUseReleaseLevels() {
        val (mdns, listener) = discover(AdbMdns.TLS_CONNECT, mutableListOf())
        listener.onServiceFound(service(5555))
        idle()
        listener.onServiceLost(service(5555))
        idle()
        listener.onStopDiscoveryFailed(AdbMdns.TLS_CONNECT, NsdManager.FAILURE_INTERNAL_ERROR)
        mdns.stop()
        idle()
        val id = entries("Discovery start: ").single().field("listener")
        for (prefix in listOf(
            "Discovery start: ", "Discovery started: ", "Service found: ", "Resolve issued: ",
            "Service lost: ", "Discovery stop: ", "Discovery stopped: ",
        )) {
            val lines = entries(prefix)
            assertTrue(prefix, lines.isNotEmpty())
            assertTrue(prefix, lines.all { it.type == Log.INFO && it.field("listener") == id })
        }
        assertEquals("S1", entries("Service lost: ").single().field("service"))
        assertEquals("true", entries("Discovery stop: ").single().field("registered"))
        assertEquals("false", entries("Discovery stopped: ").last().field("active"))
        val stopFailed = entries("Stopping discovery failed: ${NsdManager.FAILURE_INTERNAL_ERROR} ").single()
        assertEquals(Log.WARN, stopFailed.type)
        assertEquals(id, stopFailed.field("listener"))
    }

    @Test fun failedStopOfStaleListenerIsLoggedAsWarning() {
        val mdns = AdbMdns(context, AdbMdns.TLS_PAIRING) {}
        mdns.start()
        val old = nsd.getDiscoveryListeners(AdbMdns.TLS_PAIRING)!!.single()
        mdns.stop()
        old.onDiscoveryStarted(AdbMdns.TLS_PAIRING)
        idle()
        old.onDiscoveryStarted(AdbMdns.TLS_PAIRING)
        idle()
        val id = entries("Discovery start: ").single().field("listener")
        val failures = entries("Cannot stop discovery: ")
        assertTrue(failures.isNotEmpty())
        assertTrue(failures.all { it.type == Log.WARN && it.throwable != null && it.field("listener") == id })
    }

    private companion object {
        const val SERIAL = "R5CT1234567"
        const val SERVICE_NAME = "adb-$SERIAL-Xy12ab"
    }
}
