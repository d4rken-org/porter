package rikka.shizuku.server;

import java.util.Arrays;

import eu.darken.porter.protocol.PorterProtocol;

final class ClientRouting {

    /** The binder wire a client is served over. */
    enum Wire { PORTER, SHIZUKU }

    private static final String SHIZUKU_PROVIDER_AUTHORITY_SUFFIX = ".shizuku";

    static boolean requests(String[] permissions, String permission) {
        return permissions != null && Arrays.asList(permissions).contains(permission);
    }

    /** The wire this client is entitled to, or null for no delivery. */
    static Wire route(String[] permissions, boolean trustedCompanion) {
        if (requests(permissions, ServerConstants.PERMISSION)) return Wire.PORTER;
        if (trustedCompanion && requests(permissions, ServerConstants.LEGACY_PERMISSION)) return Wire.SHIZUKU;
        return null;
    }

    /** The authority suffix the provider receiving the binder is registered under. */
    static String providerSuffix(Wire wire) {
        return wire == Wire.PORTER ? PorterProtocol.PROVIDER_AUTHORITY_SUFFIX : SHIZUKU_PROVIDER_AUTHORITY_SUFFIX;
    }

    private ClientRouting() {}
}
