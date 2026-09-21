package eu.darken.porter.privileged

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.content.pm.Signature
import android.os.Build
import eu.darken.porter.privileged.util.Android17Compat

internal object Compatibility {

    fun isAvailable(): Boolean {
        try {
            val flags = PackageManager.GET_PERMISSIONS or (if (Build.VERSION.SDK_INT >= 28)
                PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
            val manager = Android17Compat.getPackageInfo(PorterServer.MANAGER_APPLICATION_ID, flags.toLong(), 0)
            val companion = Android17Compat.getPackageInfo(ServerConstants.COMPAT_APPLICATION_ID, flags.toLong(), 0)
            if (manager == null || companion == null || companion.permissions == null) return false
            val expected = signatures(manager)
            val actual = signatures(companion)
            if (expected == null || actual == null || expected.isEmpty() || actual.isEmpty()
                || expected.toHashSet() != actual.toHashSet()) return false
            for (permission in companion.permissions) {
                if (ServerConstants.LEGACY_PERMISSION == permission.name
                    && ServerConstants.COMPAT_APPLICATION_ID == permission.packageName
                    && (permission.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE) == PermissionInfo.PROTECTION_DANGEROUS) {
                    return true
                }
            }
        } catch (ignored: Throwable) {
            // Package removal or replacement can race a binder delivery.
        }
        return false
    }

    fun signatures(info: PackageInfo): Array<Signature>? {
        if (Build.VERSION.SDK_INT >= 28) {
            return info.signingInfo?.apkContentsSigners
        }
        return info.signatures
    }
}
