package rikka.shizuku.server;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import eu.darken.porter.common.CompatibilitySetup;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import rikka.hidden.compat.PackageManagerApis;
import rikka.hidden.compat.UserManagerApis;
import rikka.shizuku.server.util.Android17Compat;
import java.security.MessageDigest;
import java.util.List;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class CompatibilitySetupHandlerTest {
    @Test public void inspectionIncludesOtherInstalledUsersAndFailsWhenUsersUnknown() throws Exception {
        try (MockedStatic<UserManagerApis> users = mockStatic(UserManagerApis.class);
             MockedStatic<Android17Compat> packages = mockStatic(Android17Compat.class);
             MockedStatic<Compatibility> companion = mockStatic(Compatibility.class)) {
            users.when(UserManagerApis::getUserIdsNoThrow).thenReturn(List.of(0, 10));
            PackageInfo installed = new PackageInfo();
            installed.applicationInfo = new ApplicationInfo();
            installed.applicationInfo.flags = ApplicationInfo.FLAG_INSTALLED;
            packages.when(() -> Android17Compat.getPackageInfo(eq(ServerConstants.COMPAT_APPLICATION_ID), anyLong(), eq(10))).thenReturn(installed);
            CompatibilitySetupHandler handler = new CompatibilitySetupHandler(mock(ShizukuConfigManager.class), (uid, flags) -> {}, () -> {});
            assertArrayEquals(new int[]{10}, handler.execute(CompatibilitySetup.INSPECT, null).getIntArray("users"));
            users.when(UserManagerApis::getUserIdsNoThrow).thenReturn(List.of());
            assertThrows(IllegalStateException.class, () -> handler.execute(CompatibilitySetup.INSPECT, null));
        }
    }

    @Test public void failedDecisionOrDurableSaveCannotReturnSuccessfulImport() throws Exception {
        try (MockedStatic<Android17Compat> packages = mockStatic(Android17Compat.class);
             MockedStatic<PackageManagerApis> uids = mockStatic(PackageManagerApis.class);
             MockedStatic<Compatibility> companion = mockStatic(Compatibility.class)) {
            companion.when(Compatibility::isAvailable).thenReturn(true);
            Signature signature = new Signature(new byte[]{1, 2, 3});
            PackageInfo installed = new PackageInfo();
            installed.packageName = "test.client";
            installed.applicationInfo = new ApplicationInfo();
            installed.applicationInfo.uid = 10123;
            installed.requestedPermissions = new String[]{ServerConstants.LEGACY_PERMISSION};
            packages.when(() -> Android17Compat.getPackageInfo(eq("test.client"), anyLong(), eq(0))).thenReturn(installed);
            uids.when(() -> PackageManagerApis.getPackagesForUidNoThrow(10123)).thenReturn(List.of("test.client"));
            companion.when(() -> Compatibility.signatures(installed)).thenReturn(new Signature[]{signature});
            LegacyAccessImport.Decision decision = new LegacyAccessImport.Decision();
            decision.packageName = "test.client";
            decision.uid = 10123;
            decision.flags = LegacyAccessImport.ALLOW;
            StringBuilder digest = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())) digest.append(String.format("%02x", b & 255));
            decision.certificate = digest.toString();
            String json = LegacyAccessImport.encode(List.of(decision));
            ShizukuConfigManager config = mock(ShizukuConfigManager.class);
            Runnable refresh = mock(Runnable.class);
            CompatibilitySetupHandler failingWriter = new CompatibilitySetupHandler(config, (uid, flags) -> { throw new IllegalStateException("Package changed"); }, refresh);
            assertThrows(IllegalStateException.class, () -> failingWriter.execute(CompatibilitySetup.APPLY_IMPORT, json));
            verify(config, never()).persistImport();
            verifyNoInteractions(refresh);

            doThrow(new IllegalStateException("Disk unavailable")).doNothing().when(config).persistImport();
            CompatibilitySetupHandler handler = new CompatibilitySetupHandler(config, (uid, flags) -> {}, refresh);
            assertThrows(IllegalStateException.class, () -> handler.execute(CompatibilitySetup.APPLY_IMPORT, json));
            verifyNoInteractions(refresh);
            assertEquals(1, handler.execute(CompatibilitySetup.APPLY_IMPORT, json).getInt("applied"));
            verify(refresh).run();
        }
    }
}
