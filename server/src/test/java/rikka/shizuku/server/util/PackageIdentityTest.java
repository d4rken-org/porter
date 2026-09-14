package rikka.shizuku.server.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;

import rikka.hidden.compat.PackageManagerApis;

/** Three-way classification, and the signer comparison the identity model rests on. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class PackageIdentityTest {

    static final String PACKAGE = "eu.darken.porter.probe";
    static final int UID = 10123;

    static final Signature ORIGINAL = new Signature("0a0b");
    static final Signature ROTATED = new Signature("0c0d");
    static final Signature FOREIGN = new Signature("0e0f");
    static final Signature SECOND = new Signature("1a1b");

    private MockedStatic<PackageManagerApis> packages;

    @Before
    public void setup() throws Exception {
        packages = Mockito.mockStatic(PackageManagerApis.class);
        compatField("sPackageManager", null);
        compatField("sGetPackageInfoMethod", null);
    }

    @After
    public void teardown() {
        packages.close();
    }

    private static void compatField(String name, Object value) throws Exception {
        Field field = Android17Compat.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    /** A package installed for one user, signed by {@code signers} with lineage {@code history}. */
    static PackageInfo installed(Signature[] signers, Signature[] history) {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = PACKAGE;
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.applicationInfo.uid = UID;
        packageInfo.applicationInfo.flags = ApplicationInfo.FLAG_INSTALLED;
        SigningInfo signingInfo = mock(SigningInfo.class);
        when(signingInfo.hasMultipleSigners()).thenReturn(signers.length > 1);
        when(signingInfo.getApkContentsSigners()).thenReturn(signers);
        when(signingInfo.getSigningCertificateHistory()).thenReturn(signers.length > 1 ? null : history);
        packageInfo.signingInfo = signingInfo;
        return packageInfo;
    }

    static PackageIdentity.Identity identityOf(Signature... signers) {
        return PackageIdentity.identityOf(installed(signers, signers));
    }

    static PackageIdentity.Observed observe(PackageInfo packageInfo, int userId) {
        PackageIdentity.Result result = PackageIdentity.classify(packageInfo, userId);
        assertEquals(PackageIdentity.State.PRESENT, result.state);
        return result.observed;
    }

    @Test
    public void aHiddenPackageIsStillInstalled() {
        // pm hide leaves the installed bit set, and MATCH_UNINSTALLED_PACKAGES still returns it.
        PackageInfo hidden = installed(new Signature[]{ORIGINAL}, new Signature[]{ORIGINAL});
        assertEquals(PackageIdentity.State.PRESENT, PackageIdentity.classify(hidden, 0).state);
    }

    @Test
    public void aRetainedDataResidueIsAbsent() {
        PackageInfo residue = installed(new Signature[]{ORIGINAL}, new Signature[]{ORIGINAL});
        residue.applicationInfo.flags &= ~ApplicationInfo.FLAG_INSTALLED;
        assertEquals(PackageIdentity.State.ABSENT, PackageIdentity.classify(residue, 0).state);
    }

    @Test
    public void aMissingPackageIsAbsent() {
        assertEquals(PackageIdentity.State.ABSENT, PackageIdentity.classify(null, 0).state);
    }

    @Test
    public void anAnswerWithoutSignersIsAFailureRatherThanEvidence() {
        PackageInfo unreadable = installed(new Signature[]{ORIGINAL}, new Signature[]{ORIGINAL});
        when(unreadable.signingInfo.getApkContentsSigners()).thenReturn(new Signature[0]);
        assertEquals(PackageIdentity.State.LOOKUP_FAILED, PackageIdentity.classify(unreadable, 0).state);
    }

    @Test
    public void aFailedLookupIsNeverReportedAsAbsence() {
        packages.when(() -> PackageManagerApis.getPackageInfo(anyString(), anyLong(), anyInt()))
                .thenThrow(new SecurityException("Shell cannot query across users"));

        PackageIdentity.Result result = PackageIdentity.of(PACKAGE, 0);
        assertEquals(PackageIdentity.State.LOOKUP_FAILED, result.state);
        assertTrue(result.cause instanceof SecurityException);
    }

    @Test
    public void aMissingHiddenMethodAlsoReportsFailureWhenTheFallbackCannotAnswer() {
        packages.when(() -> PackageManagerApis.getPackageInfo(anyString(), anyLong(), anyInt()))
                .thenThrow(new NoSuchMethodError("getPackageInfo"));

        assertEquals(PackageIdentity.State.LOOKUP_FAILED, PackageIdentity.of(PACKAGE, 0).state);
    }

    @Test
    public void reconciliationStillWorksThroughTheReflectionFallback() throws Exception {
        packages.when(() -> PackageManagerApis.getPackageInfo(anyString(), anyLong(), anyInt()))
                .thenThrow(new NoSuchMethodError("getPackageInfo"));
        Android17CompatTest.FakePackageManager fake = new Android17CompatTest.FakePackageManager();
        fake.answer = installed(new Signature[]{ORIGINAL}, new Signature[]{ORIGINAL});
        compatField("sPackageManager", fake);

        PackageIdentity.Result result = PackageIdentity.of(PACKAGE, 10);
        assertEquals(PackageIdentity.State.PRESENT, result.state);
        assertEquals(UID, result.observed.appId);
    }

    @Test
    public void aWorkingLookupIsPresentWithTheCallersAppId() {
        PackageInfo present = installed(new Signature[]{ORIGINAL}, new Signature[]{ORIGINAL});
        packages.when(() -> PackageManagerApis.getPackageInfo(anyString(), anyLong(), anyInt()))
                .thenReturn(present);

        PackageIdentity.Result result = PackageIdentity.of(PACKAGE, 10);
        assertEquals(PackageIdentity.State.PRESENT, result.state);
        assertEquals(UID, result.observed.appId);
        assertEquals(10, result.observed.userId);
    }

    @Test
    public void theLookupFlagsAskForSignaturesAndForUninstalledPackages() {
        long flags = PackageIdentity.lookupFlags();
        assertTrue((flags & PackageManager.MATCH_UNINSTALLED_PACKAGES) != 0);
        assertTrue((flags & PackageManager.GET_SIGNING_CERTIFICATES) != 0);
    }

    @Test
    public void anOrdinaryUpdateBySameSignerMatches() {
        PackageIdentity.Identity recorded = identityOf(ORIGINAL);
        assertTrue(recorded.matches(observe(installed(new Signature[]{ORIGINAL}, new Signature[]{ORIGINAL}), 0)));
    }

    @Test
    public void aRotatedKeyMatchesThroughItsLineage() {
        PackageIdentity.Identity recorded = identityOf(ORIGINAL);
        PackageInfo rotated = installed(new Signature[]{ROTATED}, new Signature[]{ORIGINAL, ROTATED});
        assertTrue(recorded.matches(observe(rotated, 0)));
    }

    @Test
    public void aForeignSignerDoesNotMatch() {
        PackageIdentity.Identity recorded = identityOf(ORIGINAL);
        PackageInfo foreign = installed(new Signature[]{FOREIGN}, new Signature[]{FOREIGN});
        assertFalse(recorded.matches(observe(foreign, 0)));
    }

    @Test
    public void aMultiSignerPackageComparesAsAnUnorderedSet() {
        PackageIdentity.Identity recorded = identityOf(ORIGINAL, SECOND);
        PackageInfo permuted = installed(new Signature[]{SECOND, ORIGINAL}, null);
        assertTrue(recorded.matches(observe(permuted, 0)));
    }

    @Test
    public void aMultiSignerPackageWithAForeignSignerDoesNotMatch() {
        // hasMultipleSigners() means no lineage exists, so the whole set is the identity.
        PackageIdentity.Identity recorded = identityOf(ORIGINAL, SECOND);
        PackageInfo replaced = installed(new Signature[]{ORIGINAL, FOREIGN}, null);
        assertFalse(recorded.matches(observe(replaced, 0)));
    }

    @Test
    public void oneCoSignerOfATwoSignerIdentityIsNotThatIdentity() {
        // Recorded as signed by ORIGINAL and SECOND together. A package signed by ORIGINAL alone,
        // carrying ORIGINAL in its lineage, dropped a signing authority; that is a different
        // installation, not a rotation of the pair.
        PackageIdentity.Identity recorded = identityOf(ORIGINAL, SECOND);
        PackageInfo dropped = installed(new Signature[]{ORIGINAL}, new Signature[]{ORIGINAL});
        assertFalse("a single co-signer matched a two-signer identity",
                recorded.matches(observe(dropped, 0)));
    }

    @Test
    public void aDifferentAppIdDoesNotMatchEvenWithTheSameSigner() {
        PackageIdentity.Identity recorded = identityOf(ORIGINAL);
        PackageInfo moved = installed(new Signature[]{ORIGINAL}, new Signature[]{ORIGINAL});
        moved.applicationInfo.uid = UID + 1;
        assertFalse(recorded.matches(observe(moved, 0)));
    }

    @Test
    public void anUnreadableSignatureDoesNotBecomeAnIdentityWithoutSigners() {
        PackageInfo unreadable = installed(new Signature[]{ORIGINAL}, new Signature[]{ORIGINAL});
        when(unreadable.signingInfo.getApkContentsSigners()).thenReturn(new Signature[0]);
        // The identical reading, through the other entry point, is a failure rather than an answer.
        assertEquals(PackageIdentity.State.LOOKUP_FAILED, PackageIdentity.classify(unreadable, 0).state);

        PackageIdentity.Identity identity = PackageIdentity.identityOf(unreadable);
        assertTrue("identityOf produced an identity with no signers: " + identity,
                identity == null || !identity.signerDigests.isEmpty());
    }

    @Test
    public void twoUsersReportingDifferentSignersIsUnstableRatherThanAReplacement() {
        PackageIdentity.Observed first = observe(installed(new Signature[]{ORIGINAL}, new Signature[]{ORIGINAL}), 0);
        PackageIdentity.Observed second = observe(installed(new Signature[]{FOREIGN}, new Signature[]{FOREIGN}), 10);
        assertFalse(PackageIdentity.sameInstallation(first, second));
        assertTrue(PackageIdentity.sameInstallation(first, first));
    }
}
