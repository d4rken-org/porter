package rikka.shizuku.server;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.Signature;
import android.os.Build;

import java.util.Arrays;
import java.util.HashSet;

import rikka.shizuku.server.util.Android17Compat;

final class Compatibility {

    static boolean isAvailable() {
        try {
            int flags = PackageManager.GET_PERMISSIONS | (Build.VERSION.SDK_INT >= 28
                    ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES);
            PackageInfo manager = Android17Compat.getPackageInfo(ShizukuService.MANAGER_APPLICATION_ID, flags, 0);
            PackageInfo companion = Android17Compat.getPackageInfo(ServerConstants.COMPAT_APPLICATION_ID, flags, 0);
            if (manager == null || companion == null || companion.permissions == null) return false;
            Signature[] expected = signatures(manager);
            Signature[] actual = signatures(companion);
            if (expected == null || actual == null || expected.length == 0 || actual.length == 0
                    || !new HashSet<>(Arrays.asList(expected)).equals(new HashSet<>(Arrays.asList(actual)))) return false;
            for (PermissionInfo permission : companion.permissions) {
                if (ServerConstants.LEGACY_PERMISSION.equals(permission.name)
                        && ServerConstants.COMPAT_APPLICATION_ID.equals(permission.packageName)
                        && (permission.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE) == PermissionInfo.PROTECTION_DANGEROUS) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // Package removal or replacement can race a binder delivery.
        }
        return false;
    }

    private static Signature[] signatures(PackageInfo info) {
        if (Build.VERSION.SDK_INT >= 28) {
            return info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners();
        }
        return info.signatures;
    }

    private Compatibility() {}
}
