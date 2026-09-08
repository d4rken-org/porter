package rikka.shizuku.server;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.Parcel;
import eu.darken.porter.common.DiscoveredApplication;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import rikka.parcelablelist.ParcelableListSlice;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;
import static eu.darken.porter.common.DiscoveredApplication.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class ApplicationDiscoveryTest {
    static PackageInfo app(String name, int uid, String... permissions) {
        PackageInfo info = new PackageInfo();
        info.packageName = name;
        info.firstInstallTime = 100;
        info.requestedPermissions = permissions;
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = name;
        info.applicationInfo.uid = uid;
        info.applicationInfo.metaData = new Bundle();
        info.applicationInfo.metaData.putBoolean("moe.shizuku.client.V3_SUPPORT", true);
        return info;
    }
    @Test public void dualClientUsesPorterWithoutCompanionAndPreservesAuthorization() {
        var info = app("dual", 1012345, ServerConstants.PERMISSION, ServerConstants.LEGACY_PERMISSION);
        info.applicationInfo.metaData.putBoolean("moe.shizuku.client.V3_REQUIRES_ROOT", true);
        var entry = ApplicationDiscovery.describe(info, ConfigManager.FLAG_DENIED, false, 123456);
        assertEquals(API_PORTER | API_SHIZUKU, entry.declaredApis);
        assertEquals(DIRECT, entry.connectionStatus);
        assertEquals(DENIED, entry.authorization);
        assertEquals(10, entry.userId);
        assertTrue(entry.requiresRoot);
        assertNull(entry.applicationInfo.metaData);
        assertEquals(123456, entry.lastConnectedAt);
    }
    @Test public void legacyClientRemainsVisibleWithoutCompanionButDoesNotClaimDirectSupport() {
        var info = app("legacy", 10123, ServerConstants.LEGACY_PERMISSION);
        assertEquals(NEEDS_COMPANION, ApplicationDiscovery.describe(info, 0, false, 0).connectionStatus);
        assertEquals(COMPANION, ApplicationDiscovery.describe(info, 0, true, 0).connectionStatus);
    }
    @Test public void permissionDeclarationAloneDoesNotPromiseIntegration() {
        var info = app("declaration", 10123, ServerConstants.PERMISSION);
        info.applicationInfo.metaData = null;
        var entry = ApplicationDiscovery.describe(info, 0, true, 0);
        assertEquals(UNSUPPORTED, entry.connectionStatus);
        assertFalse(entry.requiresRoot);
    }
    @Test public void terminalsWithHistoryOrDecisionsRemainVisibleWithoutDeclarations() {
        var info = app("terminal", 10123);
        assertNull(ApplicationDiscovery.describe(info, 0, false, 0));
        assertEquals(MANAGED_ONLY, ApplicationDiscovery.describe(info, 0, false, 123).connectionStatus);
        assertEquals(ALLOWED, ApplicationDiscovery.describe(info, ConfigManager.FLAG_ALLOWED, false, 0).authorization);
    }
    @Test public void oversizedMetadataAndSplitListsCannotProduceOversizedEntry() {
        var info = app("large", 10123, ServerConstants.PERMISSION);
        info.applicationInfo.metaData.putString("large", "x".repeat(1024 * 1024));
        info.applicationInfo.splitSourceDirs = new String[]{"/data/app/" + "x".repeat(100000)};
        info.applicationInfo.sourceDir = "/data/app/large/base.apk";
        var entry = ApplicationDiscovery.describe(info, 0, false, 0);
        assertNull(entry.applicationInfo.metaData);
        assertNull(entry.applicationInfo.splitSourceDirs);
        assertTrue(ApplicationDiscovery.parcelSize(entry) < 48 * 1024);
        assertEquals("large", entry.applicationInfo.packageName);
    }
    @Test public void entryRoundTripSkipsFutureTrailingFields() {
        var entry = ApplicationDiscovery.describe(app("example", 1001234, ServerConstants.PERMISSION), ConfigManager.FLAG_ALLOWED, false, 9876);
        Parcel parcel = Parcel.obtain();
        try {
            entry.writeToParcel(parcel, 0);
            parcel.writeString("future optional field");
            int end = parcel.dataPosition();
            parcel.setDataPosition(0); parcel.writeInt(end); parcel.setDataPosition(end);
            parcel.writeInt(7654);
            parcel.setDataPosition(0);
            var copy = DiscoveredApplication.CREATOR.createFromParcel(parcel);
            assertEquals("example", copy.applicationInfo.packageName);
            assertEquals(entry.userId, copy.userId);
            assertEquals(entry.lastConnectedAt, copy.lastConnectedAt);
            assertEquals(entry.authorization, copy.authorization);
            assertEquals(7654, parcel.readInt());
        } finally { parcel.recycle(); }
    }
    @Test public void listLargerThanBinderBufferRoundTripsThroughMultipleSlices() {
        List<DiscoveredApplication> entries = new ArrayList<>();
        long total = 0;
        for (int i = 0; i < 3000; i++) {
            var entry = ApplicationDiscovery.describe(app("test.app" + i, 10000 + i, ServerConstants.PERMISSION), 0, false, i);
            total += ApplicationDiscovery.parcelSize(entry);
            entries.add(entry);
        }
        assertTrue(total > 1024 * 1024);
        Parcel parcel = Parcel.obtain();
        try {
            new ParcelableListSlice<>(entries).writeToParcel(parcel, 0);
            assertTrue(parcel.dataSize() < 128 * 1024);
            parcel.setDataPosition(0);
            @SuppressWarnings("unchecked")
            List<DiscoveredApplication> copies = ((ParcelableListSlice<DiscoveredApplication>) ParcelableListSlice.CREATOR.createFromParcel(parcel)).getList();
            assertEquals(entries.size(), copies.size());
            for (int i = 0; i < entries.size(); i++) {
                assertEquals("test.app" + i, copies.get(i).applicationInfo.packageName);
                assertEquals(i, copies.get(i).lastConnectedAt);
            }
        } finally { parcel.recycle(); }
    }
    @Test public void pendingIntentIsSeparateFromEffectiveAuthorization() {
        var entry = ApplicationDiscovery.describe(app("pending", 10123, ServerConstants.LEGACY_PERMISSION),
            ShizukuConfig.FLAG_PENDING_COMPANION, false, 0);
        assertEquals(eu.darken.porter.common.DiscoveredApplication.PENDING_COMPANION, entry.authorization);
        assertEquals(eu.darken.porter.common.DiscoveredApplication.NEEDS_COMPANION, entry.connectionStatus);
    }
}
