package rikka.shizuku.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import eu.darken.porter.protocol.PorterProtocol;
import rikka.shizuku.server.ClientRouting.Wire;

public class ClientRoutingTest {

    private static final String[] PORTER_ONLY = {ServerConstants.PERMISSION};
    private static final String[] LEGACY_ONLY = {ServerConstants.LEGACY_PERMISSION};
    private static final String[] BOTH = {ServerConstants.LEGACY_PERMISSION, ServerConstants.PERMISSION};

    @Test
    public void porterPermissionAlwaysRoutesToThePorterWire() {
        assertEquals(Wire.PORTER, ClientRouting.route(PORTER_ONLY, false));
        assertEquals(Wire.PORTER, ClientRouting.route(PORTER_ONLY, true));
        assertEquals(Wire.PORTER, ClientRouting.route(BOTH, true));
        assertEquals(Wire.PORTER, ClientRouting.route(BOTH, false));
    }

    @Test
    public void legacyPermissionRoutesOnlyWithTrustedCompanion() {
        assertEquals(Wire.SHIZUKU, ClientRouting.route(LEGACY_ONLY, true));
        assertNull(ClientRouting.route(LEGACY_ONLY, false));
    }

    @Test
    public void unrelatedPackagesAreNotClients() {
        assertNull(ClientRouting.route(null, true));
        assertNull(ClientRouting.route(new String[]{"android.permission.INTERNET"}, true));
    }

    @Test
    public void eachWireNamesTheProviderItsSdkDeclares() {
        assertEquals(PorterProtocol.PROVIDER_AUTHORITY_SUFFIX, ClientRouting.providerSuffix(Wire.PORTER));
        assertEquals(".shizuku", ClientRouting.providerSuffix(Wire.SHIZUKU));
    }

    /** The authority the pre-D3 server delivered to; no wire may be answered there any more. */
    @Test
    public void noWireDeliversToTheRetiredPorterAuthority() {
        for (Wire wire : Wire.values()) assertNotEquals(".porter", ClientRouting.providerSuffix(wire));
    }
}
