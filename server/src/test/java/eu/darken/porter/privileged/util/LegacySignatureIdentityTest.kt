package eu.darken.porter.privileged.util

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Below API 28 only the deprecated signature array exists. Proof of rotation is itself an API 28
 * feature, so the platform rejects an update whose key differs and a changed signature can only have
 * come from an uninstall and a foreign reinstall.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], manifest = Config.NONE)
class LegacySignatureIdentityTest {

    @Test
    fun theSameSignatureStillMatches() {
        val recorded = PackageIdentity.identityOf(installed(ORIGINAL))!!
        assertTrue(recorded.matches(observe(installed(ORIGINAL))))
    }

    @Test
    fun aChangedSignatureDoesNotMatch() {
        val recorded = PackageIdentity.identityOf(installed(ORIGINAL))!!
        assertFalse(recorded.matches(observe(installed(FOREIGN))))
    }

    @Test
    fun theLookupFlagsFallBackToTheDeprecatedSignatureArray() {
        val flags = PackageIdentity.lookupFlags()
        @Suppress("DEPRECATION")
        assertTrue((flags and PackageManager.GET_SIGNATURES.toLong()) != 0L)
        assertTrue((flags and PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong()) != 0L)
    }

    private companion object {
        val ORIGINAL = Signature("0a0b")
        val FOREIGN = Signature("0e0f")

        @Suppress("DEPRECATION")
        fun installed(vararg signatures: Signature): PackageInfo {
            val packageInfo = PackageInfo()
            packageInfo.packageName = "eu.darken.porter.probe"
            val applicationInfo = ApplicationInfo()
            applicationInfo.uid = 10123
            applicationInfo.flags = ApplicationInfo.FLAG_INSTALLED
            packageInfo.applicationInfo = applicationInfo
            packageInfo.signatures = arrayOf(*signatures)
            return packageInfo
        }

        fun observe(packageInfo: PackageInfo): PackageIdentity.Observed {
            val result = PackageIdentity.classify(packageInfo, 0)
            assertEquals(PackageIdentity.State.PRESENT, result.state)
            return result.observed!!
        }
    }
}
