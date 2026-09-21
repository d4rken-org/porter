package eu.darken.porter.privileged

import eu.darken.porter.privileged.ClientRouting.Wire
import eu.darken.porter.protocol.PorterProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClientRoutingTest {

    @Test
    fun porterPermissionAlwaysRoutesToThePorterWire() {
        assertEquals(Wire.PORTER, ClientRouting.route(PORTER_ONLY, false))
        assertEquals(Wire.PORTER, ClientRouting.route(PORTER_ONLY, true))
        assertEquals(Wire.PORTER, ClientRouting.route(BOTH, true))
        assertEquals(Wire.PORTER, ClientRouting.route(BOTH, false))
    }

    @Test
    fun legacyPermissionRoutesOnlyWithTrustedCompanion() {
        assertEquals(Wire.SHIZUKU, ClientRouting.route(LEGACY_ONLY, true))
        assertNull(ClientRouting.route(LEGACY_ONLY, false))
    }

    @Test
    fun unrelatedPackagesAreNotClients() {
        assertNull(ClientRouting.route(null, true))
        assertNull(ClientRouting.route(arrayOf("android.permission.INTERNET"), true))
    }

    @Test
    fun eachWireNamesTheProviderItsSdkDeclares() {
        assertEquals(PorterProtocol.PROVIDER_AUTHORITY_SUFFIX, ClientRouting.providerSuffix(Wire.PORTER))
        assertEquals(".shizuku", ClientRouting.providerSuffix(Wire.SHIZUKU))
    }

    /** The authority the pre-D3 server delivered to; no wire may be answered there any more. */
    @Test
    fun noWireDeliversToTheRetiredPorterAuthority() {
        for (wire in Wire.entries) assertNotEquals(".porter", ClientRouting.providerSuffix(wire))
    }

    private companion object {
        val PORTER_ONLY = arrayOf(ServerConstants.PERMISSION)
        val LEGACY_ONLY = arrayOf(ServerConstants.LEGACY_PERMISSION)
        val BOTH = arrayOf(ServerConstants.LEGACY_PERMISSION, ServerConstants.PERMISSION)
    }
}
