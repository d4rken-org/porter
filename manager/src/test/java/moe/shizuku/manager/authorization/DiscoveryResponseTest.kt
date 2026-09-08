package moe.shizuku.manager.authorization

import android.content.pm.ApplicationInfo
import android.os.Binder
import android.os.Parcel
import eu.darken.porter.common.DiscoveredApplication
import moe.shizuku.manager.TestApplication
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import rikka.parcelablelist.ParcelableListSlice

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [34])
class DiscoveryResponseTest {
    @Test fun oldServiceFallsBackWithoutReadingAnEmptyReply() {
        val old = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int) = false
        }
        assertNull(AuthorizationManager.readDiscovery(old))
    }
    @Test fun unsupportedVersionFallsBackBeforeParsingEntries() {
        val future = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                reply!!.writeNoException()
                reply.writeInt(DiscoveredApplication.WIRE_VERSION + 1)
                return true
            }
        }
        assertNull(AuthorizationManager.readDiscovery(future))
    }
    @Test fun responsePreservesPartialProfilesAndAuthorization() {
        val info = ApplicationInfo().apply { packageName = "example"; uid = 10123 }
        val entry = DiscoveredApplication(info, 0, DiscoveredApplication.API_SHIZUKU,
            DiscoveredApplication.DENIED, DiscoveredApplication.NEEDS_COMPANION, false, 1234)
        val service = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                assertEquals(DiscoveredApplication.TRANSACTION, code)
                data.enforceInterface("moe.shizuku.server.IShizukuService")
                assertEquals(-1, data.readInt())
                reply!!.writeNoException()
                reply.writeInt(DiscoveredApplication.WIRE_VERSION)
                reply.writeIntArray(intArrayOf(10))
                ParcelableListSlice(listOf(entry)).writeToParcel(reply, 0)
                return true
            }
        }
        val response = AuthorizationManager.readDiscovery(service)!!
        assertEquals(listOf(10), response.failedUsers)
        assertFalse(response.legacy)
        assertEquals(DiscoveredApplication.DENIED, response.apps.single().authorization)
        assertEquals(1234L, response.apps.single().lastConnectedAt)
    }
}
