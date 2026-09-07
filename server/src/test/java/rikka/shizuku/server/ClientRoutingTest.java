package rikka.shizuku.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ClientRoutingTest {

    private static final String[] PORTER_ONLY = {ServerConstants.PERMISSION};
    private static final String[] LEGACY_ONLY = {ServerConstants.LEGACY_PERMISSION};
    private static final String[] BOTH = {ServerConstants.LEGACY_PERMISSION, ServerConstants.PERMISSION};

    @Test
    public void porterPermissionAlwaysRoutesToPorterEndpoint() {
        assertEquals(".porter", ClientRouting.providerSuffix(PORTER_ONLY, false));
        assertEquals(".porter", ClientRouting.providerSuffix(PORTER_ONLY, true));
        assertEquals(".porter", ClientRouting.providerSuffix(BOTH, true));
    }

    @Test
    public void legacyPermissionRoutesOnlyWithTrustedCompanion() {
        assertEquals(".shizuku", ClientRouting.providerSuffix(LEGACY_ONLY, true));
        assertNull(ClientRouting.providerSuffix(LEGACY_ONLY, false));
    }

    @Test
    public void unrelatedPackagesAreNotClients() {
        assertNull(ClientRouting.providerSuffix(null, true));
        assertNull(ClientRouting.providerSuffix(new String[]{"android.permission.INTERNET"}, true));
    }
}
