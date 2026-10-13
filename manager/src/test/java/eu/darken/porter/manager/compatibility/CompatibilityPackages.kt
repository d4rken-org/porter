package eu.darken.porter.manager.compatibility

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PermissionInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import eu.darken.porter.manager.BuildConfig
import org.robolectric.Shadows.shadowOf

/** Shadow package fixtures for the compatibility tests; Robolectric runs them at SDK 34. */
internal class CompatibilityPackages(private val application: Application) {
    private val packages = shadowOf(application.packageManager)
    val porterSigner = Signature(byteArrayOf(1, 2, 3))
    val otherSigner = Signature(byteArrayOf(4, 5, 6))

    fun signPorter() {
        checkNotNull(packages.getInternalMutablePackageInfo(application.packageName)).signingInfo = signingInfo(porterSigner)
    }

    val porterCertificate: String get() = CompatibilityRepository.certificate(
        application.packageManager.getPackageInfo(application.packageName, CompatibilityRepository.PACKAGE_FLAGS))

    fun permission(owner: String) = PermissionInfo().apply {
        name = CompatibilityRepository.PERMISSION
        packageName = owner
        protectionLevel = PermissionInfo.PROTECTION_DANGEROUS
    }

    fun install(
        pkg: String,
        signer: Signature,
        definesPermission: Boolean = true,
        system: Boolean = false,
        version: Long = BuildConfig.COMPAT_VERSION_CODE.toLong(),
    ) {
        packages.removePackage(pkg)
        packages.installPackage(PackageInfo().apply {
            packageName = pkg
            applicationInfo = ApplicationInfo().apply {
                packageName = pkg
                if (system) flags = flags or ApplicationInfo.FLAG_SYSTEM
            }
            if (definesPermission) permissions = arrayOf(permission(pkg))
            signingInfo = signingInfo(signer)
            longVersionCode = version
        })
    }

    fun remove(pkg: String) = packages.removePackage(pkg)

    /** Defines the permission for [owner] without installing it, as for an owner Porter cannot see. */
    fun hiddenOwner(owner: String) = packages.addPermissionInfo(permission(owner))

    private fun signingInfo(signer: Signature) = SigningInfo().also { shadowOf(it).setSignatures(arrayOf(signer)) }
}
