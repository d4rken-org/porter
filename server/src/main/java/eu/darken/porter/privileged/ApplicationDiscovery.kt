package eu.darken.porter.privileged

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Parcel
import eu.darken.porter.common.DiscoveredApplication
import eu.darken.porter.common.DiscoveredApplication.Companion.ALLOWED
import eu.darken.porter.common.DiscoveredApplication.Companion.API_PORTER
import eu.darken.porter.common.DiscoveredApplication.Companion.API_SHIZUKU
import eu.darken.porter.common.DiscoveredApplication.Companion.COMPANION
import eu.darken.porter.common.DiscoveredApplication.Companion.DEFAULT
import eu.darken.porter.common.DiscoveredApplication.Companion.DENIED
import eu.darken.porter.common.DiscoveredApplication.Companion.DIRECT
import eu.darken.porter.common.DiscoveredApplication.Companion.MANAGED_ONLY
import eu.darken.porter.common.DiscoveredApplication.Companion.NEEDS_COMPANION
import eu.darken.porter.common.DiscoveredApplication.Companion.PENDING_COMPANION
import eu.darken.porter.common.DiscoveredApplication.Companion.UNSUPPORTED
import rikka.shizuku.server.ConfigManager

internal object ApplicationDiscovery {
    fun describe(info: PackageInfo, flags: Int, companion: Boolean, lastConnected: Long): DiscoveredApplication? {
        val source = info.applicationInfo ?: return null
        // Manager apps stay off the list, but only while they have nothing to show there: anything
        // can define a permission by that name, and a decision the user cannot see is one they
        // cannot revoke.
        val permissions = info.permissions
        if (permissions != null && flags == 0 && lastConnected == 0L) {
            for (permission in permissions) {
                val name = permission.name
                if (name != null && name.endsWith(".permission.MANAGER")) return null
            }
        }
        val apis = (if (ClientRouting.requests(info.requestedPermissions, ServerConstants.PERMISSION)) API_PORTER else 0) or
            (if (ClientRouting.requests(info.requestedPermissions, ServerConstants.LEGACY_PERMISSION)) API_SHIZUKU else 0)
        if (apis == 0 && flags == 0 && lastConnected == 0L) return null
        // The Porter permission is Porter's own support signal. V3_SUPPORT survives only as the
        // Shizuku-client marker, which is upstream's to define.
        val metaData = source.metaData
        val shizukuReady = (apis and API_SHIZUKU) != 0 && metaData != null
            && metaData.getBoolean("moe.shizuku.client.V3_SUPPORT", false)
        val status = if (apis == 0) MANAGED_ONLY
        else if ((apis and API_PORTER) != 0) DIRECT
        else if (shizukuReady) (if (companion) COMPANION else NEEDS_COMPANION)
        else UNSUPPORTED
        val authorization = if ((flags and ShizukuConfig.FLAG_PENDING_COMPANION) != 0) PENDING_COMPANION
        else if ((flags and ConfigManager.FLAG_ALLOWED) != 0) ALLOWED
        else if ((flags and ConfigManager.FLAG_DENIED) != 0) DENIED
        else DEFAULT
        val root = metaData != null && metaData.getBoolean("moe.shizuku.client.V3_REQUIRES_ROOT", false)
        val display = ApplicationInfo()
        display.packageName = info.packageName
        display.uid = source.uid
        display.name = source.name
        display.labelRes = source.labelRes
        val nonLocalizedLabel = source.nonLocalizedLabel
        display.nonLocalizedLabel = if (nonLocalizedLabel == null) null
        else nonLocalizedLabel.toString().substring(0, minOf(512, nonLocalizedLabel.length))
        display.icon = source.icon
        display.sourceDir = source.sourceDir
        display.publicSourceDir = source.publicSourceDir
        display.splitSourceDirs = source.splitSourceDirs
        display.splitPublicSourceDirs = source.splitPublicSourceDirs
        val result = DiscoveredApplication(display, source.uid / 100000, apis, authorization, status, root, lastConnected)
        if (parcelSize(result) > 48 * 1024) {
            display.splitSourceDirs = null
            display.splitPublicSourceDirs = null
            display.sourceDir = null
            display.publicSourceDir = null
            display.name = null
            display.labelRes = 0
            display.icon = 0
        }
        if (parcelSize(result) > 48 * 1024) throw IllegalArgumentException("Discovery entry too large")
        return result
    }

    fun parcelSize(entry: DiscoveredApplication): Int {
        val parcel = Parcel.obtain()
        try {
            entry.writeToParcel(parcel, 0)
            return parcel.dataSize()
        } finally {
            parcel.recycle()
        }
    }
}
