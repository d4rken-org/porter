package eu.darken.porter.privileged

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Bundle
import android.os.Parcel
import eu.darken.porter.common.DiscoveredApplication
import eu.darken.porter.common.DiscoveredApplication.Companion.ALLOWED
import eu.darken.porter.common.DiscoveredApplication.Companion.API_PORTER
import eu.darken.porter.common.DiscoveredApplication.Companion.API_SHIZUKU
import eu.darken.porter.common.DiscoveredApplication.Companion.COMPANION
import eu.darken.porter.common.DiscoveredApplication.Companion.DENIED
import eu.darken.porter.common.DiscoveredApplication.Companion.DIRECT
import eu.darken.porter.common.DiscoveredApplication.Companion.MANAGED_ONLY
import eu.darken.porter.common.DiscoveredApplication.Companion.NEEDS_COMPANION
import eu.darken.porter.common.DiscoveredApplication.Companion.PENDING_COMPANION
import eu.darken.porter.common.DiscoveredApplication.Companion.UNSUPPORTED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import rikka.parcelablelist.ParcelableListSlice
import rikka.shizuku.server.ConfigManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ApplicationDiscoveryTest {

    @Test
    fun dualClientUsesPorterWithoutCompanionAndPreservesAuthorization() {
        val info = app("dual", 1012345, ServerConstants.PERMISSION, ServerConstants.LEGACY_PERMISSION)
        info.applicationInfo!!.metaData.putBoolean("moe.shizuku.client.V3_REQUIRES_ROOT", true)
        val entry = ApplicationDiscovery.describe(info, ConfigManager.FLAG_DENIED, false, 123456)!!
        assertEquals(API_PORTER or API_SHIZUKU, entry.declaredApis)
        assertEquals(DIRECT, entry.connectionStatus)
        assertEquals(DENIED, entry.authorization)
        assertEquals(10, entry.userId)
        assertTrue(entry.requiresRoot)
        assertNull(entry.applicationInfo.metaData)
        assertEquals(123456, entry.lastConnectedAt)
    }

    @Test
    fun legacyClientRemainsVisibleWithoutCompanionButDoesNotClaimDirectSupport() {
        val info = app("legacy", 10123, ServerConstants.LEGACY_PERMISSION)
        assertEquals(NEEDS_COMPANION, ApplicationDiscovery.describe(info, 0, false, 0)!!.connectionStatus)
        assertEquals(COMPANION, ApplicationDiscovery.describe(info, 0, true, 0)!!.connectionStatus)
    }

    @Test
    fun thePorterPermissionIsItsOwnSupportSignal() {
        val info = app("declaration", 10123, ServerConstants.PERMISSION)
        info.applicationInfo!!.metaData = null
        val entry = ApplicationDiscovery.describe(info, 0, true, 0)!!
        assertEquals(DIRECT, entry.connectionStatus)
        assertFalse(entry.requiresRoot)
    }

    @Test
    fun aShizukuClientStillHasToCarryTheShizukuMarker() {
        val info = app("legacy", 10123, ServerConstants.LEGACY_PERMISSION)
        info.applicationInfo!!.metaData.putBoolean("moe.shizuku.client.V3_SUPPORT", false)
        assertEquals(UNSUPPORTED, ApplicationDiscovery.describe(info, 0, true, 0)!!.connectionStatus)
        info.applicationInfo!!.metaData.putBoolean("moe.shizuku.client.V3_SUPPORT", true)
        assertEquals(COMPANION, ApplicationDiscovery.describe(info, 0, true, 0)!!.connectionStatus)
    }

    @Test
    fun terminalsWithHistoryOrDecisionsRemainVisibleWithoutDeclarations() {
        val info = app("terminal", 10123)
        assertNull(ApplicationDiscovery.describe(info, 0, false, 0))
        assertEquals(MANAGED_ONLY, ApplicationDiscovery.describe(info, 0, false, 123)!!.connectionStatus)
        assertEquals(ALLOWED, ApplicationDiscovery.describe(info, ConfigManager.FLAG_ALLOWED, false, 0)!!.authorization)
    }

    @Test
    fun oversizedMetadataAndSplitListsCannotProduceOversizedEntry() {
        val info = app("large", 10123, ServerConstants.PERMISSION)
        info.applicationInfo!!.metaData.putString("large", "x".repeat(1024 * 1024))
        info.applicationInfo!!.splitSourceDirs = arrayOf("/data/app/" + "x".repeat(100000))
        info.applicationInfo!!.sourceDir = "/data/app/large/base.apk"
        val entry = ApplicationDiscovery.describe(info, 0, false, 0)!!
        assertNull(entry.applicationInfo.metaData)
        assertNull(entry.applicationInfo.splitSourceDirs)
        assertTrue(ApplicationDiscovery.parcelSize(entry) < 48 * 1024)
        assertEquals("large", entry.applicationInfo.packageName)
    }

    @Test
    fun entryRoundTripSkipsFutureTrailingFields() {
        val entry = ApplicationDiscovery.describe(app("example", 1001234, ServerConstants.PERMISSION), ConfigManager.FLAG_ALLOWED, false, 9876)!!
        val parcel = Parcel.obtain()
        try {
            entry.writeToParcel(parcel, 0)
            parcel.writeString("future optional field")
            val end = parcel.dataPosition()
            parcel.setDataPosition(0)
            parcel.writeInt(end)
            parcel.setDataPosition(end)
            parcel.writeInt(7654)
            parcel.setDataPosition(0)
            val copy = DiscoveredApplication.CREATOR.createFromParcel(parcel)
            assertEquals("example", copy.applicationInfo.packageName)
            assertEquals(entry.userId, copy.userId)
            assertEquals(entry.lastConnectedAt, copy.lastConnectedAt)
            assertEquals(entry.authorization, copy.authorization)
            assertEquals(7654, parcel.readInt())
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun listLargerThanBinderBufferRoundTripsThroughMultipleSlices() {
        val entries = ArrayList<DiscoveredApplication>()
        var total = 0L
        for (i in 0 until 3000) {
            val entry = ApplicationDiscovery.describe(app("test.app$i", 10000 + i, ServerConstants.PERMISSION), 0, false, i.toLong())!!
            total += ApplicationDiscovery.parcelSize(entry)
            entries.add(entry)
        }
        assertTrue(total > 1024 * 1024)
        val parcel = Parcel.obtain()
        try {
            ParcelableListSlice(entries).writeToParcel(parcel, 0)
            assertTrue(parcel.dataSize() < 128 * 1024)
            parcel.setDataPosition(0)
            @Suppress("UNCHECKED_CAST")
            val copies = (ParcelableListSlice.CREATOR.createFromParcel(parcel) as ParcelableListSlice<DiscoveredApplication>).list
            assertEquals(entries.size, copies.size)
            for (i in entries.indices) {
                assertEquals("test.app$i", copies[i].applicationInfo.packageName)
                assertEquals(i.toLong(), copies[i].lastConnectedAt)
            }
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun pendingIntentIsSeparateFromEffectiveAuthorization() {
        val entry = ApplicationDiscovery.describe(
            app("pending", 10123, ServerConstants.LEGACY_PERMISSION),
            ShizukuConfig.FLAG_PENDING_COMPANION, false, 0,
        )!!
        assertEquals(PENDING_COMPANION, entry.authorization)
        assertEquals(NEEDS_COMPANION, entry.connectionStatus)
    }

    private companion object {
        fun app(name: String, uid: Int, vararg permissions: String): PackageInfo {
            val info = PackageInfo()
            info.packageName = name
            info.firstInstallTime = 100
            info.requestedPermissions = arrayOf(*permissions)
            val applicationInfo = ApplicationInfo()
            applicationInfo.packageName = name
            applicationInfo.uid = uid
            applicationInfo.metaData = Bundle()
            applicationInfo.metaData.putBoolean("moe.shizuku.client.V3_SUPPORT", true)
            info.applicationInfo = applicationInfo
            return info
        }
    }
}
