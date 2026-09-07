package rikka.shizuku.server;

import java.util.Arrays;

final class ClientRouting {

    static boolean requests(String[] permissions, String permission) {
        return permissions != null && Arrays.asList(permissions).contains(permission);
    }

    static String providerSuffix(String[] permissions, boolean trustedCompanion) {
        if (requests(permissions, ServerConstants.PERMISSION)) return ".porter";
        if (trustedCompanion && requests(permissions, ServerConstants.LEGACY_PERMISSION)) return ".shizuku";
        return null;
    }

    private ClientRouting() {}
}
