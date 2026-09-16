package eu.darken.porter.privileged;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBinder;

import moe.shizuku.server.IShizukuServiceConnection;
import rikka.hidden.compat.PackageManagerApis;
import rikka.shizuku.ShizukuApiConstants;
import rikka.shizuku.server.util.HandlerUtil;
import rikka.shizuku.server.UserServiceRecord;

/**
 * A differently signed APK installed over the same package name satisfies the package-name ownership
 * check on its own, so without an identity comparison it inherited the live privileged daemon the
 * original signer's app had started. Both hand-over paths are covered: reuse, and the noCreate
 * branch, which broadcasts the existing binder without ever creating a record.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class UserServiceBindIdentityTest {

    private static final String PACKAGE = "eu.darken.porter.probe";
    private static final String CLASS = "ProbeService";
    private static final int UID = 10123;

    private static final Signature ORIGINAL = new Signature("0a0b");
    private static final Signature FOREIGN = new Signature("0e0f");

    private ShizukuUserServiceManager manager;
    private MockedStatic<PackageManagerApis> packages;

    @Before
    public void setup() {
        HandlerUtil.setMainHandler(mock(Handler.class));
        packages = Mockito.mockStatic(PackageManagerApis.class);
        ShadowBinder.setCallingUid(UID);
        manager = new ShizukuUserServiceManager();
        manager.setReconciler(mock(ApkReconciler.class));
        installedWith(ORIGINAL);
    }

    @After
    public void teardown() {
        packages.close();
        ShadowBinder.reset();
    }

    private void installedWith(Signature... signers) {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = PACKAGE;
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.applicationInfo.uid = UID;
        packageInfo.applicationInfo.flags = ApplicationInfo.FLAG_INSTALLED;
        packageInfo.applicationInfo.sourceDir = "/data/app/probe/base.apk";
        SigningInfo signingInfo = mock(SigningInfo.class);
        when(signingInfo.hasMultipleSigners()).thenReturn(signers.length > 1);
        when(signingInfo.getApkContentsSigners()).thenReturn(signers);
        when(signingInfo.getSigningCertificateHistory()).thenReturn(signers.length > 1 ? null : signers);
        packageInfo.signingInfo = signingInfo;
        packages.when(() -> PackageManagerApis.getPackageInfoNoThrow(eq(PACKAGE), anyLong(), anyInt()))
                .thenReturn(packageInfo);
    }

    /** Signatures the package manager refuses to hand back at all. */
    private void installedWithUnreadableIdentity() {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = PACKAGE;
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.applicationInfo.uid = UID;
        packageInfo.applicationInfo.flags = ApplicationInfo.FLAG_INSTALLED;
        packageInfo.applicationInfo.sourceDir = "/data/app/probe/base.apk";
        packages.when(() -> PackageManagerApis.getPackageInfoNoThrow(eq(PACKAGE), anyLong(), anyInt()))
                .thenReturn(packageInfo);
    }

    private static IShizukuServiceConnection connection() {
        return new IShizukuServiceConnection.Stub() {
            @Override public void connected(IBinder service) {}
            @Override public void died() {}
        };
    }

    private static Bundle options(boolean noCreate) {
        Bundle options = new Bundle();
        options.putParcelable(ShizukuApiConstants.USER_SERVICE_ARG_COMPONENT, new ComponentName(PACKAGE, CLASS));
        options.putInt(ShizukuApiConstants.USER_SERVICE_ARG_VERSION_CODE, 1);
        options.putBoolean(ShizukuApiConstants.USER_SERVICE_ARG_DAEMON, true);
        options.putBoolean(ShizukuApiConstants.USER_SERVICE_ARG_NO_CREATE, noCreate);
        return options;
    }

    private int bind(boolean noCreate) {
        return manager.addUserService(connection(), options(noCreate), ShizukuApiConstants.SERVER_VERSION);
    }

    /** Brings a record all the way up, with a live binder, the way a real launch would. */
    private UserServiceRecord established() throws Exception {
        bind(false);
        UserServiceRecord record = manager.snapshotHosts().get(0).record;
        IBinder binder = mock(IBinder.class);
        when(binder.pingBinder()).thenReturn(true);
        when(binder.getInterfaceDescriptor()).thenReturn("eu.darken.porter.probe.IProbe");
        when(binder.transact(anyInt(), any(), any(), anyInt())).thenReturn(true);
        Bundle attach = new Bundle();
        attach.putString(ShizukuApiConstants.USER_SERVICE_ARG_TOKEN, record.token);
        manager.attachUserService(binder, attach);
        return record;
    }

    @Test
    public void theSameInstallationKeepsItsService() throws Exception {
        UserServiceRecord record = established();

        assertEquals(1, bind(true));
        assertSame(record, manager.snapshotHosts().get(0).record);
        assertTrue(manager.isUserServiceTokenLive(record.token));
    }

    @Test
    public void aDifferentSignerDoesNotReceiveTheOldBinderOnTheReusePath() throws Exception {
        UserServiceRecord record = established();
        installedWith(FOREIGN);

        bind(false);

        assertTrue(record.isRemoved());
        UserServiceRecord replacement = manager.snapshotHosts().get(0).record;
        assertNotEquals(record.token, replacement.token);
        assertNull(replacement.service);
    }

    @Test
    public void aDifferentSignerDoesNotReceiveTheOldBinderOnTheNoCreatePath() throws Exception {
        UserServiceRecord record = established();
        installedWith(FOREIGN);

        // -1 is "no service for you"; the old daemon's binder is never broadcast.
        assertEquals(-1, bind(true));
        assertTrue(record.isRemoved());
        assertEquals(0, manager.snapshotHosts().size());
    }

    @Test
    public void anUnreadableIdentityDefersRatherThanReusing() throws Exception {
        UserServiceRecord record = established();
        installedWithUnreadableIdentity();

        assertEquals(-1, bind(true));
        assertTrue(record.isRemoved());
    }

    @Test
    public void theIdentityComesFromTheLookupThatAuthorisedTheBind() {
        bind(false);
        ShizukuUserServiceManager.HostSnapshot snapshot = manager.snapshotHosts().get(0);

        assertEquals(PACKAGE, snapshot.packageName);
        assertEquals(UID, snapshot.identity.appId);
        assertEquals(1, snapshot.identity.signerDigests.size());
        // Only one lookup: the hook is handed the PackageInfo that authorised the bind.
        packages.verify(() -> PackageManagerApis.getPackageInfoNoThrow(eq(PACKAGE), anyLong(), anyInt()));
    }

    @Test
    public void pausingAccessRemovesEveryRecordedHost() throws Exception {
        UserServiceRecord record = established();

        manager.setAccessPaused(true);

        assertTrue(record.isRemoved());
        assertEquals(0, manager.snapshotHosts().size());
    }

    @Test
    public void aRemovedRecordLeavesTheHostMap() throws Exception {
        UserServiceRecord record = established();
        assertNotNull(manager.snapshotHosts().get(0));

        record.removeSelf();

        assertEquals(0, manager.snapshotHosts().size());
        assertFalse(manager.removeIfPresent(record));
    }
}
