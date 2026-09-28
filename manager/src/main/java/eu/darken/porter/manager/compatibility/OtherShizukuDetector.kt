package eu.darken.porter.manager.compatibility

import android.content.Context
import android.content.pm.PackageManager

/**
 * Whether an app other than Porter's own compatibility companion defines the permission Shizuku
 * clients request. Anything that cannot be verified as the companion counts as another install.
 */
internal object OtherShizukuDetector {

    fun isInstalled(context: Context): Boolean {
        return try {
            val pm = context.packageManager
            val owner = try {
                pm.getPermissionInfo(CompatibilityRepository.PERMISSION, 0)?.packageName
            } catch (_: PackageManager.NameNotFoundException) {
                null
            } ?: return false
            if (owner != CompatibilityRepository.PACKAGE) return true
            val ownerCertificate = CompatibilityRepository.certificate(pm.getPackageInfo(owner, CompatibilityRepository.PACKAGE_FLAGS))
            val porterCertificate = CompatibilityRepository.certificate(pm.getPackageInfo(context.packageName, CompatibilityRepository.PACKAGE_FLAGS))
            ownerCertificate != porterCertificate
        } catch (_: Exception) {
            true
        }
    }
}
