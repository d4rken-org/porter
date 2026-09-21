package eu.darken.porter.privileged

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Build
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.MockedStatic
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.times
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import rikka.hidden.compat.PackageManagerApis

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class CompatibilityTest {
    private lateinit var packages: MockedStatic<PackageManagerApis>
    private lateinit var manager: PackageInfo
    private lateinit var companion: PackageInfo
    private lateinit var permission: PermissionInfo

    @Before
    fun setup() {
        manager = PackageInfo()
        companion = PackageInfo()
        permission = PermissionInfo()
        permission.name = ServerConstants.LEGACY_PERMISSION
        permission.packageName = ServerConstants.COMPAT_APPLICATION_ID
        permission.protectionLevel = PermissionInfo.PROTECTION_DANGEROUS
        companion.permissions = arrayOf(permission)
        sign(manager, Signature("aabb"))
        sign(companion, Signature("aabb"))
        packages = mockStatic(PackageManagerApis::class.java)
        answer(PorterServer.MANAGER_APPLICATION_ID) { manager }
        answer(ServerConstants.COMPAT_APPLICATION_ID) { companion }
    }

    @After
    fun close() {
        packages.close()
    }

    /**
     * What a user-0 lookup of [packageName] answers. `Android17Compat` is a Kotlin object, which a
     * static mock cannot intercept, so the stub sits one level below it on the hidden-API call the
     * compat layer makes.
     */
    private fun answer(packageName: String, answer: () -> PackageInfo?) {
        packages.`when`<PackageInfo?> { PackageManagerApis.getPackageInfo(anyString(), anyLong(), anyInt()) }
            .thenAnswer { invocation ->
                if (invocation.getArgument<Int>(2) != 0) return@thenAnswer null
                when (invocation.getArgument<String>(0)) {
                    packageName -> answer()
                    PorterServer.MANAGER_APPLICATION_ID -> manager
                    ServerConstants.COMPAT_APPLICATION_ID -> companion
                    else -> null
                }
            }
    }

    @Suppress("DEPRECATION")
    private fun sign(info: PackageInfo, vararg certificates: Signature) {
        if (Build.VERSION.SDK_INT >= 28) {
            val signingInfo = mock(SigningInfo::class.java)
            `when`(signingInfo.apkContentsSigners).thenReturn(arrayOf(*certificates))
            info.signingInfo = signingInfo
        } else info.signatures = arrayOf(*certificates)
    }

    @Test
    fun matchingCurrentCertificatesAndOwnedDangerousPermissionAreTrusted() {
        assertTrue(Compatibility.isAvailable())
        packages.verify {
            PackageManagerApis.getPackageInfo(
                PorterServer.MANAGER_APPLICATION_ID,
                (PackageManager.GET_PERMISSIONS or PackageManager.GET_SIGNING_CERTIFICATES).toLong(), 0,
            )
        }
    }

    @Test
    @Config(sdk = [27])
    @Suppress("DEPRECATION")
    fun preAndroidNineUsesLegacySignatureField() {
        assertTrue(Compatibility.isAvailable())
        sign(companion, Signature("ccdd"))
        assertFalse(Compatibility.isAvailable())
        packages.verify(
            {
                PackageManagerApis.getPackageInfo(
                    PorterServer.MANAGER_APPLICATION_ID,
                    (PackageManager.GET_PERMISSIONS or PackageManager.GET_SIGNATURES).toLong(), 0,
                )
            },
            times(2),
        )
    }

    @Test
    fun foreignCertificateCannotOwnLegacyAccess() {
        sign(companion, Signature("ccdd"))
        assertFalse(Compatibility.isAvailable())
    }

    @Test
    fun allCurrentSignersMustMatchButOrderDoesNotMatter() {
        sign(manager, Signature("aabb"), Signature("ccdd"))
        assertFalse(Compatibility.isAvailable())
        sign(companion, Signature("ccdd"), Signature("aabb"))
        assertTrue(Compatibility.isAvailable())
    }

    @Test
    fun historicalSignerDoesNotSubstituteForCurrentSigner() {
        sign(companion, Signature("ccdd"))
        `when`(companion.signingInfo!!.signingCertificateHistory).thenReturn(arrayOf(Signature("aabb")))
        assertFalse(Compatibility.isAvailable())
    }

    @Test
    fun emptyOrMissingSignaturesFailClosed() {
        sign(companion)
        assertFalse(Compatibility.isAvailable())
        companion.signingInfo = null
        assertFalse(Compatibility.isAvailable())
        sign(companion, Signature("aabb"))
        manager.signingInfo = null
        assertFalse(Compatibility.isAvailable())
    }

    @Test
    fun missingCompanionAndLookupFailureFailClosed() {
        answer(ServerConstants.COMPAT_APPLICATION_ID) { null }
        assertFalse(Compatibility.isAvailable())
        answer(ServerConstants.COMPAT_APPLICATION_ID) { throw IllegalStateException("Package replaced") }
        assertFalse(Compatibility.isAvailable())
    }

    @Test
    fun missingManagerFailsClosed() {
        answer(PorterServer.MANAGER_APPLICATION_ID) { null }
        assertFalse(Compatibility.isAvailable())
    }

    @Test
    fun wrongPermissionOwnerOrNameIsRejected() {
        permission.packageName = "foreign.manager"
        assertFalse(Compatibility.isAvailable())
        permission.packageName = ServerConstants.COMPAT_APPLICATION_ID
        permission.name = ServerConstants.PERMISSION
        assertFalse(Compatibility.isAvailable())
    }

    @Test
    fun normalOrSignaturePermissionCannotReplaceRuntimeConsent() {
        permission.protectionLevel = PermissionInfo.PROTECTION_NORMAL
        assertFalse(Compatibility.isAvailable())
        permission.protectionLevel = PermissionInfo.PROTECTION_SIGNATURE
        assertFalse(Compatibility.isAvailable())
    }

    @Test
    fun absentPermissionDeclarationIsRejected() {
        companion.permissions = null
        assertFalse(Compatibility.isAvailable())
        companion.permissions = arrayOf()
        assertFalse(Compatibility.isAvailable())
    }
}
