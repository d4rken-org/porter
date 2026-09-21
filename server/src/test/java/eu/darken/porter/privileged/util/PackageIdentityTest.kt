package eu.darken.porter.privileged.util

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import rikka.hidden.compat.PackageManagerApis

/** Three-way classification, and the signer comparison the identity model rests on. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PackageIdentityTest {

    private lateinit var packages: MockedStatic<PackageManagerApis>

    @Before
    fun setup() {
        packages = Mockito.mockStatic(PackageManagerApis::class.java)
        Android17CompatTest.set("sPackageManager", null)
        Android17CompatTest.set("sGetPackageInfoMethod", null)
    }

    @After
    fun teardown() {
        packages.close()
    }

    private fun directCallThrows(failure: Throwable) {
        packages.`when`<PackageInfo?> { PackageManagerApis.getPackageInfo(anyString(), anyLong(), anyInt()) }
            .thenThrow(failure)
    }

    @Test
    fun aHiddenPackageIsStillInstalled() {
        // pm hide leaves the installed bit set, and MATCH_UNINSTALLED_PACKAGES still returns it.
        val hidden = installed(arrayOf(ORIGINAL), arrayOf(ORIGINAL))
        assertEquals(PackageIdentity.State.PRESENT, PackageIdentity.classify(hidden, 0).state)
    }

    @Test
    fun aRetainedDataResidueIsAbsent() {
        val residue = installed(arrayOf(ORIGINAL), arrayOf(ORIGINAL))
        residue.applicationInfo!!.flags = residue.applicationInfo!!.flags and ApplicationInfo.FLAG_INSTALLED.inv()
        assertEquals(PackageIdentity.State.ABSENT, PackageIdentity.classify(residue, 0).state)
    }

    @Test
    fun aMissingPackageIsAbsent() {
        assertEquals(PackageIdentity.State.ABSENT, PackageIdentity.classify(null, 0).state)
    }

    @Test
    fun anAnswerWithoutSignersIsAFailureRatherThanEvidence() {
        val unreadable = installed(arrayOf(ORIGINAL), arrayOf(ORIGINAL))
        `when`(unreadable.signingInfo!!.apkContentsSigners).thenReturn(arrayOf())
        assertEquals(PackageIdentity.State.LOOKUP_FAILED, PackageIdentity.classify(unreadable, 0).state)
    }

    @Test
    fun aFailedLookupIsNeverReportedAsAbsence() {
        directCallThrows(SecurityException("Shell cannot query across users"))

        val result = PackageIdentity.of(PACKAGE, 0)
        assertEquals(PackageIdentity.State.LOOKUP_FAILED, result.state)
        assertTrue(result.cause is SecurityException)
    }

    @Test
    fun aMissingHiddenMethodAlsoReportsFailureWhenTheFallbackCannotAnswer() {
        directCallThrows(NoSuchMethodError("getPackageInfo"))

        assertEquals(PackageIdentity.State.LOOKUP_FAILED, PackageIdentity.of(PACKAGE, 0).state)
    }

    @Test
    fun reconciliationStillWorksThroughTheReflectionFallback() {
        directCallThrows(NoSuchMethodError("getPackageInfo"))
        val fake = Android17CompatTest.FakePackageManager()
        fake.answer = installed(arrayOf(ORIGINAL), arrayOf(ORIGINAL))
        Android17CompatTest.set("sPackageManager", fake)

        val result = PackageIdentity.of(PACKAGE, 10)
        assertEquals(PackageIdentity.State.PRESENT, result.state)
        assertEquals(UID, result.observed!!.appId)
    }

    @Test
    fun aWorkingLookupIsPresentWithTheCallersAppId() {
        val present = installed(arrayOf(ORIGINAL), arrayOf(ORIGINAL))
        packages.`when`<PackageInfo?> { PackageManagerApis.getPackageInfo(anyString(), anyLong(), anyInt()) }
            .thenReturn(present)

        val result = PackageIdentity.of(PACKAGE, 10)
        assertEquals(PackageIdentity.State.PRESENT, result.state)
        assertEquals(UID, result.observed!!.appId)
        assertEquals(10, result.observed!!.userId)
    }

    @Test
    fun theLookupFlagsAskForSignaturesAndForUninstalledPackages() {
        val flags = PackageIdentity.lookupFlags()
        assertTrue((flags and PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong()) != 0L)
        assertTrue((flags and PackageManager.GET_SIGNING_CERTIFICATES.toLong()) != 0L)
    }

    @Test
    fun anOrdinaryUpdateBySameSignerMatches() {
        val recorded = identityOf(ORIGINAL)
        assertTrue(recorded.matches(observe(installed(arrayOf(ORIGINAL), arrayOf(ORIGINAL)), 0)))
    }

    @Test
    fun aRotatedKeyMatchesThroughItsLineage() {
        val recorded = identityOf(ORIGINAL)
        val rotated = installed(arrayOf(ROTATED), arrayOf(ORIGINAL, ROTATED))
        assertTrue(recorded.matches(observe(rotated, 0)))
    }

    @Test
    fun aForeignSignerDoesNotMatch() {
        val recorded = identityOf(ORIGINAL)
        val foreign = installed(arrayOf(FOREIGN), arrayOf(FOREIGN))
        assertFalse(recorded.matches(observe(foreign, 0)))
    }

    @Test
    fun aMultiSignerPackageComparesAsAnUnorderedSet() {
        val recorded = identityOf(ORIGINAL, SECOND)
        val permuted = installed(arrayOf(SECOND, ORIGINAL), null)
        assertTrue(recorded.matches(observe(permuted, 0)))
    }

    @Test
    fun aMultiSignerPackageWithAForeignSignerDoesNotMatch() {
        // hasMultipleSigners() means no lineage exists, so the whole set is the identity.
        val recorded = identityOf(ORIGINAL, SECOND)
        val replaced = installed(arrayOf(ORIGINAL, FOREIGN), null)
        assertFalse(recorded.matches(observe(replaced, 0)))
    }

    @Test
    fun oneCoSignerOfATwoSignerIdentityIsNotThatIdentity() {
        // Recorded as signed by ORIGINAL and SECOND together. A package signed by ORIGINAL alone,
        // carrying ORIGINAL in its lineage, dropped a signing authority; that is a different
        // installation, not a rotation of the pair.
        val recorded = identityOf(ORIGINAL, SECOND)
        val dropped = installed(arrayOf(ORIGINAL), arrayOf(ORIGINAL))
        assertFalse("a single co-signer matched a two-signer identity", recorded.matches(observe(dropped, 0)))
    }

    @Test
    fun aDifferentAppIdDoesNotMatchEvenWithTheSameSigner() {
        val recorded = identityOf(ORIGINAL)
        val moved = installed(arrayOf(ORIGINAL), arrayOf(ORIGINAL))
        moved.applicationInfo!!.uid = UID + 1
        assertFalse(recorded.matches(observe(moved, 0)))
    }

    @Test
    fun anUnreadableSignatureDoesNotBecomeAnIdentityWithoutSigners() {
        val unreadable = installed(arrayOf(ORIGINAL), arrayOf(ORIGINAL))
        `when`(unreadable.signingInfo!!.apkContentsSigners).thenReturn(arrayOf())
        // The identical reading, through the other entry point, is a failure rather than an answer.
        assertEquals(PackageIdentity.State.LOOKUP_FAILED, PackageIdentity.classify(unreadable, 0).state)

        val identity = PackageIdentity.identityOf(unreadable)
        assertTrue(
            "identityOf produced an identity with no signers: $identity",
            identity == null || identity.signerDigests.isNotEmpty(),
        )
    }

    @Test
    fun twoUsersReportingDifferentSignersIsUnstableRatherThanAReplacement() {
        val first = observe(installed(arrayOf(ORIGINAL), arrayOf(ORIGINAL)), 0)
        val second = observe(installed(arrayOf(FOREIGN), arrayOf(FOREIGN)), 10)
        assertFalse(PackageIdentity.sameInstallation(first, second))
        assertTrue(PackageIdentity.sameInstallation(first, first))
    }

    companion object {
        const val PACKAGE = "eu.darken.porter.probe"
        const val UID = 10123

        val ORIGINAL = Signature("0a0b")
        val ROTATED = Signature("0c0d")
        val FOREIGN = Signature("0e0f")
        val SECOND = Signature("1a1b")

        /** A package installed for one user, signed by [signers] with lineage [history]. */
        fun installed(signers: Array<Signature>, history: Array<Signature>?): PackageInfo {
            val packageInfo = PackageInfo()
            packageInfo.packageName = PACKAGE
            val applicationInfo = ApplicationInfo()
            applicationInfo.uid = UID
            applicationInfo.flags = ApplicationInfo.FLAG_INSTALLED
            packageInfo.applicationInfo = applicationInfo
            val signingInfo = mock(SigningInfo::class.java)
            `when`(signingInfo.hasMultipleSigners()).thenReturn(signers.size > 1)
            `when`(signingInfo.apkContentsSigners).thenReturn(signers)
            `when`(signingInfo.signingCertificateHistory).thenReturn(if (signers.size > 1) null else history)
            packageInfo.signingInfo = signingInfo
            return packageInfo
        }

        fun identityOf(vararg signers: Signature): PackageIdentity.Identity =
            PackageIdentity.identityOf(installed(arrayOf(*signers), arrayOf(*signers)))!!

        fun observe(packageInfo: PackageInfo, userId: Int): PackageIdentity.Observed {
            val result = PackageIdentity.classify(packageInfo, userId)
            assertEquals(PackageIdentity.State.PRESENT, result.state)
            return result.observed!!
        }
    }
}
