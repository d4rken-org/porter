package eu.darken.porter.manager.compatibility

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PermissionInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.test.core.app.ApplicationProvider
import eu.darken.porter.manager.TestApplication
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [34])
class OtherShizukuDetectorTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val packages = shadowOf(application.packageManager)
    private val porterSigner = Signature(byteArrayOf(1, 2, 3))
    private val otherSigner = Signature(byteArrayOf(4, 5, 6))

    @Before fun signPorter() {
        sign(checkNotNull(packages.getInternalMutablePackageInfo(application.packageName)), porterSigner)
    }

    private fun permission(owner: String) = PermissionInfo().apply {
        name = CompatibilityRepository.PERMISSION
        packageName = owner
        protectionLevel = PermissionInfo.PROTECTION_DANGEROUS
    }

    private fun install(owner: String, signer: Signature?) {
        val info = PackageInfo().apply {
            packageName = owner
            applicationInfo = ApplicationInfo().apply { packageName = owner }
            permissions = arrayOf(permission(owner))
        }
        if (signer != null) sign(info, signer)
        packages.installPackage(info)
    }

    @Suppress("DEPRECATION")
    private fun sign(info: PackageInfo, signer: Signature) {
        if (Build.VERSION.SDK_INT >= 28) info.signingInfo = signingInfo(signer)
        else info.signatures = arrayOf(signer)
    }

    @RequiresApi(28)
    private fun signingInfo(signer: Signature) = SigningInfo().also { shadowOf(it).setSignatures(arrayOf(signer)) }

    private fun detect() = OtherShizukuDetector.isInstalled(application)

    @Test fun nothingDefinesThePermission() {
        assertFalse(detect())
    }

    @Test fun porterCompanionIsNotAnotherInstall() {
        install(CompatibilityRepository.PACKAGE, porterSigner)
        assertFalse(detect())
    }

    @Test @Config(sdk = [27]) fun porterCompanionIsRecognisedFromLegacySignatures() {
        install(CompatibilityRepository.PACKAGE, porterSigner)
        assertFalse(detect())
    }

    @Test fun originalPackageUnderAnotherSignerIsAnotherInstall() {
        install(CompatibilityRepository.PACKAGE, otherSigner)
        assertTrue(detect())
    }

    @Test @Config(sdk = [27]) fun originalPackageUnderAnotherLegacySignerIsAnotherInstall() {
        install(CompatibilityRepository.PACKAGE, otherSigner)
        assertTrue(detect())
    }

    @Test fun forkUnderAnotherPackageIsAnotherInstall() {
        install("com.example.shizukufork", porterSigner)
        assertTrue(detect())
    }

    @Test fun unreadableOwnerIsAnotherInstall() {
        packages.addPermissionInfo(permission(CompatibilityRepository.PACKAGE))
        assertTrue(detect())
    }

    @Test fun ownerWithoutSignerIsAnotherInstall() {
        install(CompatibilityRepository.PACKAGE, signer = null)
        assertTrue(detect())
    }
}
