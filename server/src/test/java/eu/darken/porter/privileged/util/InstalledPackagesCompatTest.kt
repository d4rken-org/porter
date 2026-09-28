package eu.darken.porter.privileged.util

import android.content.pm.PackageInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemProperties

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class InstalledPackagesCompatTest {

    /** Named like Android 17's return type, which the unwrapping matches on. */
    class PackageInfoList(private val list: List<PackageInfo>) {
        fun getList(): List<PackageInfo> = list
    }

    /** Stands in for IPackageManager; reached by reflection with exactly this name and types. */
    class FakePackageManager(private val answer: Any?) {
        var seenFlags = 0L
        var seenUserId = -1

        fun getInstalledPackages(flags: Long, userId: Int): Any? {
            seenFlags = flags
            seenUserId = userId
            return answer
        }
    }

    @Before
    fun setup() {
        ShadowSystemProperties.override("debug.porter.pm_fallback", "true")
    }

    @After
    fun teardown() {
        Android17CompatTest.set("sPackageManager", null)
    }

    @Test
    fun `the hidden API's Android 17 list is unwrapped`() {
        val info = PackageInfo().apply { packageName = "com.example.client" }
        val fake = FakePackageManager(PackageInfoList(listOf(info)))
        Android17CompatTest.set("sPackageManager", fake)

        val packages = InstalledPackagesCompat.getInstalledPackages(4224L, 10)

        assertEquals(listOf("com.example.client"), packages.map { it.packageName })
        assertEquals(4224L, fake.seenFlags)
        assertEquals(10, fake.seenUserId)
    }

    @Test
    fun `an unknown return type fails rather than reading as no packages`() {
        Android17CompatTest.set("sPackageManager", FakePackageManager("not a list"))

        assertThrows(IllegalStateException::class.java) {
            InstalledPackagesCompat.getInstalledPackages(0L, 0)
        }
    }
}
