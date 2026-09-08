package rikka.shizuku.server;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import rikka.shizuku.server.util.Android17Compat;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class CompatibilityTest {
    private MockedStatic<Android17Compat> packages;
    private PackageInfo manager;
    private PackageInfo companion;
    private PermissionInfo permission;

    @Before public void setup() {
        manager = new PackageInfo();
        companion = new PackageInfo();
        permission = new PermissionInfo();
        permission.name = ServerConstants.LEGACY_PERMISSION;
        permission.packageName = ServerConstants.COMPAT_APPLICATION_ID;
        permission.protectionLevel = PermissionInfo.PROTECTION_DANGEROUS;
        companion.permissions = new PermissionInfo[]{permission};
        sign(manager, new Signature("aabb"));
        sign(companion, new Signature("aabb"));
        packages = mockStatic(Android17Compat.class);
        packages.when(() -> Android17Compat.getPackageInfo(eq(ShizukuService.MANAGER_APPLICATION_ID), anyLong(), eq(0))).thenReturn(manager);
        packages.when(() -> Android17Compat.getPackageInfo(eq(ServerConstants.COMPAT_APPLICATION_ID), anyLong(), eq(0))).thenReturn(companion);
    }

    @After public void close() { packages.close(); }

    private void sign(PackageInfo info, Signature... certificates) {
        if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo = mock(SigningInfo.class);
            when(info.signingInfo.getApkContentsSigners()).thenReturn(certificates);
        } else info.signatures = certificates;
    }

    @Test public void matchingCurrentCertificatesAndOwnedDangerousPermissionAreTrusted() {
        assertTrue(Compatibility.isAvailable());
        packages.verify(() -> Android17Compat.getPackageInfo(ShizukuService.MANAGER_APPLICATION_ID,
                PackageManager.GET_PERMISSIONS | PackageManager.GET_SIGNING_CERTIFICATES, 0));
    }

    @Test @Config(sdk = 27) public void preAndroidNineUsesLegacySignatureField() {
        assertTrue(Compatibility.isAvailable());
        sign(companion, new Signature("ccdd"));
        assertFalse(Compatibility.isAvailable());
        packages.verify(() -> Android17Compat.getPackageInfo(ShizukuService.MANAGER_APPLICATION_ID,
                PackageManager.GET_PERMISSIONS | PackageManager.GET_SIGNATURES, 0), times(2));
    }

    @Test public void foreignCertificateCannotOwnLegacyAccess() {
        sign(companion, new Signature("ccdd"));
        assertFalse(Compatibility.isAvailable());
    }

    @Test public void allCurrentSignersMustMatchButOrderDoesNotMatter() {
        sign(manager, new Signature("aabb"), new Signature("ccdd"));
        assertFalse(Compatibility.isAvailable());
        sign(companion, new Signature("ccdd"), new Signature("aabb"));
        assertTrue(Compatibility.isAvailable());
    }

    @Test public void historicalSignerDoesNotSubstituteForCurrentSigner() {
        sign(companion, new Signature("ccdd"));
        when(companion.signingInfo.getSigningCertificateHistory()).thenReturn(new Signature[]{new Signature("aabb")});
        assertFalse(Compatibility.isAvailable());
    }

    @Test public void emptyOrMissingSignaturesFailClosed() {
        sign(companion);
        assertFalse(Compatibility.isAvailable());
        companion.signingInfo = null;
        assertFalse(Compatibility.isAvailable());
        sign(companion, new Signature("aabb"));
        manager.signingInfo = null;
        assertFalse(Compatibility.isAvailable());
    }

    @Test public void missingCompanionAndLookupFailureFailClosed() {
        packages.when(() -> Android17Compat.getPackageInfo(eq(ServerConstants.COMPAT_APPLICATION_ID), anyLong(), eq(0))).thenReturn(null);
        assertFalse(Compatibility.isAvailable());
        packages.when(() -> Android17Compat.getPackageInfo(eq(ServerConstants.COMPAT_APPLICATION_ID), anyLong(), eq(0))).thenThrow(new IllegalStateException("Package replaced"));
        assertFalse(Compatibility.isAvailable());
    }

    @Test public void missingManagerFailsClosed() {
        packages.when(() -> Android17Compat.getPackageInfo(eq(ShizukuService.MANAGER_APPLICATION_ID), anyLong(), eq(0))).thenReturn(null);
        assertFalse(Compatibility.isAvailable());
    }

    @Test public void wrongPermissionOwnerOrNameIsRejected() {
        permission.packageName = "foreign.manager";
        assertFalse(Compatibility.isAvailable());
        permission.packageName = ServerConstants.COMPAT_APPLICATION_ID;
        permission.name = ServerConstants.PERMISSION;
        assertFalse(Compatibility.isAvailable());
    }

    @Test public void normalOrSignaturePermissionCannotReplaceRuntimeConsent() {
        permission.protectionLevel = PermissionInfo.PROTECTION_NORMAL;
        assertFalse(Compatibility.isAvailable());
        permission.protectionLevel = PermissionInfo.PROTECTION_SIGNATURE;
        assertFalse(Compatibility.isAvailable());
    }

    @Test public void absentPermissionDeclarationIsRejected() {
        companion.permissions = null;
        assertFalse(Compatibility.isAvailable());
        companion.permissions = new PermissionInfo[0];
        assertFalse(Compatibility.isAvailable());
    }
}
