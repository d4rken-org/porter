package eu.darken.porter.privileged.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Below API 28 only the deprecated signature array exists. Proof of rotation is itself an API 28
 * feature, so the platform rejects an update whose key differs and a changed signature can only have
 * come from an uninstall and a foreign reinstall.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 27, manifest = Config.NONE)
public class LegacySignatureIdentityTest {

    private static final Signature ORIGINAL = new Signature("0a0b");
    private static final Signature FOREIGN = new Signature("0e0f");

    private static PackageInfo installed(Signature... signatures) {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = "eu.darken.porter.probe";
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.applicationInfo.uid = 10123;
        packageInfo.applicationInfo.flags = ApplicationInfo.FLAG_INSTALLED;
        packageInfo.signatures = signatures;
        return packageInfo;
    }

    private static PackageIdentity.Observed observe(PackageInfo packageInfo) {
        PackageIdentity.Result result = PackageIdentity.classify(packageInfo, 0);
        assertEquals(PackageIdentity.State.PRESENT, result.state);
        return result.observed;
    }

    @Test
    public void theSameSignatureStillMatches() {
        PackageIdentity.Identity recorded = PackageIdentity.identityOf(installed(ORIGINAL));
        assertTrue(recorded.matches(observe(installed(ORIGINAL))));
    }

    @Test
    public void aChangedSignatureDoesNotMatch() {
        PackageIdentity.Identity recorded = PackageIdentity.identityOf(installed(ORIGINAL));
        assertFalse(recorded.matches(observe(installed(FOREIGN))));
    }

    @Test
    public void theLookupFlagsFallBackToTheDeprecatedSignatureArray() {
        long flags = PackageIdentity.lookupFlags();
        assertTrue((flags & android.content.pm.PackageManager.GET_SIGNATURES) != 0);
        assertTrue((flags & android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES) != 0);
    }
}
