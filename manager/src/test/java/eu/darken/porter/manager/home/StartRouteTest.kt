package eu.darken.porter.manager.home

import org.junit.Assert.assertEquals
import org.junit.Test

class StartRouteTest {
    private val tcpPorts = listOf(-1, 0, 5555)
    private val tlsValues = listOf(false, true)

    @Test fun trustedDisabledSettingAlwaysAsksForUsbDebugging() {
        for (tcpPort in tcpPorts) for (tls in tlsValues) {
            assertEquals("tcpPort=$tcpPort tls=$tls", StartRoute.USB_DEBUGGING_OFF, startRoute(0, true, tcpPort, tls))
        }
    }

    @Test fun untrustedDisabledSettingRoutesLikeAnEnabledOne() {
        for (tcpPort in tcpPorts) for (tls in tlsValues) {
            val expected = startRoute(1, true, tcpPort, tls)
            assertEquals("tcpPort=$tcpPort tls=$tls", expected, startRoute(0, false, tcpPort, tls))
            assertEquals("tcpPort=$tcpPort tls=$tls", expected, startRoute(1, false, tcpPort, tls))
        }
    }

    @Test fun noTcpPortWithoutTlsMeansWirelessDebuggingIsUnavailable() {
        for (tcpPort in listOf(-1, 0)) {
            assertEquals(StartRoute.WIRELESS_DEBUGGING_UNAVAILABLE, startRoute(1, true, tcpPort, false))
            assertEquals(StartRoute.WIRELESS_DEBUGGING_UNAVAILABLE, startRoute(0, false, tcpPort, false))
        }
    }

    @Test fun noTcpPortWithTlsDiscoversThePort() {
        for (tcpPort in listOf(-1, 0)) {
            assertEquals(StartRoute.DISCOVER, startRoute(1, true, tcpPort, true))
            assertEquals(StartRoute.DISCOVER, startRoute(0, false, tcpPort, true))
        }
    }

    @Test fun listeningTcpPortIsUsedDirectly() {
        for (tls in tlsValues) {
            assertEquals(StartRoute.DIRECT_PORT, startRoute(1, true, 5555, tls))
            assertEquals(StartRoute.DIRECT_PORT, startRoute(0, false, 5555, tls))
        }
    }
}
