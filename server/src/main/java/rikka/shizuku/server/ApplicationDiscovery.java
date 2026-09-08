package rikka.shizuku.server;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Parcel;
import eu.darken.porter.common.DiscoveredApplication;
import static eu.darken.porter.common.DiscoveredApplication.*;

final class ApplicationDiscovery {
    static DiscoveredApplication describe(PackageInfo info, int flags, boolean companion, long lastConnected) {
        if (info.applicationInfo == null) return null;
        if (info.permissions != null) {
            for (android.content.pm.PermissionInfo permission : info.permissions) {
                if (permission.name != null && permission.name.endsWith(".permission.MANAGER")) return null;
            }
        }
        int apis = (ClientRouting.requests(info.requestedPermissions, ServerConstants.PERMISSION) ? API_PORTER : 0)
                | (ClientRouting.requests(info.requestedPermissions, ServerConstants.LEGACY_PERMISSION) ? API_SHIZUKU : 0);
        if (apis == 0 && flags == 0 && lastConnected == 0) return null;
        boolean metadata = info.applicationInfo.metaData != null
                && info.applicationInfo.metaData.getBoolean("moe.shizuku.client.V3_SUPPORT", false);
        int status;
        if (apis == 0) status = MANAGED_ONLY;
        else if (!metadata) status = UNSUPPORTED;
        else if ((apis & API_PORTER) != 0) status = DIRECT;
        else status = companion ? COMPANION : NEEDS_COMPANION;
        int authorization = (flags & ConfigManager.FLAG_ALLOWED) != 0 ? ALLOWED
                : (flags & ConfigManager.FLAG_DENIED) != 0 ? DENIED : DEFAULT;
        boolean root = info.applicationInfo.metaData != null
                && info.applicationInfo.metaData.getBoolean("moe.shizuku.client.V3_REQUIRES_ROOT", false);
        ApplicationInfo source = info.applicationInfo;
        ApplicationInfo display = new ApplicationInfo();
        display.packageName = info.packageName;
        display.uid = source.uid;
        display.name = source.name;
        display.labelRes = source.labelRes;
        display.nonLocalizedLabel = source.nonLocalizedLabel == null ? null
                : source.nonLocalizedLabel.toString().substring(0, Math.min(512, source.nonLocalizedLabel.length()));
        display.icon = source.icon;
        display.sourceDir = source.sourceDir;
        display.publicSourceDir = source.publicSourceDir;
        display.splitSourceDirs = source.splitSourceDirs;
        display.splitPublicSourceDirs = source.splitPublicSourceDirs;
        DiscoveredApplication result = new DiscoveredApplication(display, source.uid / 100000, apis, authorization, status, root, lastConnected);
        if (parcelSize(result) > 48 * 1024) {
            display.splitSourceDirs = null;
            display.splitPublicSourceDirs = null;
            display.sourceDir = null;
            display.publicSourceDir = null;
            display.name = null;
            display.labelRes = 0;
            display.icon = 0;
        }
        if (parcelSize(result) > 48 * 1024) throw new IllegalArgumentException("Discovery entry too large");
        return result;
    }

    static int parcelSize(DiscoveredApplication entry) {
        Parcel parcel = Parcel.obtain();
        try { entry.writeToParcel(parcel, 0); return parcel.dataSize(); }
        finally { parcel.recycle(); }
    }
    private ApplicationDiscovery() {}
}
