package eu.darken.porter.privileged.util

import android.content.pm.PackageInfo
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import rikka.hidden.compat.PackageManagerApis

/**
 * The fallback was unreachable: both `NoThrow` helpers catch [Throwable], so the
 * [NoSuchMethodError] the catch was written for never escaped them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class Android17CompatTest {

    /** Stands in for IPackageManager; the fallback finds its method by name and prefix types. */
    class FakePackageManager {
        var answer: PackageInfo? = null
        var failure: RuntimeException? = null
        var seenPackage: String? = null
        var seenFlags = 0L
        var seenUserId = 0

        // Reached by reflection with exactly this name and prefix types.
        fun getPackageInfo(packageName: String, flags: Long, userId: Int): PackageInfo? {
            seenPackage = packageName
            seenFlags = flags
            seenUserId = userId
            failure?.let { throw it }
            return answer
        }
    }

    private lateinit var packages: MockedStatic<PackageManagerApis>
    private lateinit var fake: FakePackageManager

    @Before
    fun setup() {
        packages = Mockito.mockStatic(PackageManagerApis::class.java)
        fake = FakePackageManager()
        set("sPackageManager", fake)
        set("sGetPackageInfoMethod", null)
    }

    @After
    fun teardown() {
        packages.close()
        set("sPackageManager", null)
        set("sGetPackageInfoMethod", null)
    }

    private fun directCallThrows(failure: Throwable) {
        packages.`when`<PackageInfo?> { PackageManagerApis.getPackageInfo(anyString(), anyLong(), anyInt()) }
            .thenThrow(failure)
    }

    @Test
    fun aMissingHiddenMethodReachesTheReflectionFallback() {
        directCallThrows(NoSuchMethodError("getPackageInfo"))
        fake.answer = PackageInfo()

        assertSame(fake.answer, Android17Compat.getPackageInfoOrThrow("eu.darken.porter", 0x2040L, 10))
        assertArrayEquals(
            arrayOf<Any?>("eu.darken.porter", 0x2040L, 10),
            arrayOf<Any?>(fake.seenPackage, fake.seenFlags, fake.seenUserId),
        )
    }

    @Test
    fun aFallbackThatAlsoFailsIsNotReportedAsAMissingPackage() {
        val missing = NoSuchMethodError("getPackageInfo")
        directCallThrows(missing)
        fake.failure = IllegalStateException("package manager is unhappy")

        val failure = assertThrows(IllegalStateException::class.java) {
            Android17Compat.getPackageInfoOrThrow("eu.darken.porter", 0, 0)
        }
        assertTrue(failure.suppressed[0] is NoSuchMethodError)
    }

    @Test
    fun aParceledSecurityExceptionReturnsNullRatherThanPropagating() {
        // The server constructor and the process-observer callback both call this with no catch.
        directCallThrows(SecurityException("Shell cannot query across users"))

        assertNull(Android17Compat.getPackageInfo("eu.darken.porter", 0, 0))
    }

    @Test
    fun theThrowingLookupStillDistinguishesAFailureFromAnAbsence() {
        directCallThrows(SecurityException("Shell cannot query across users"))

        assertThrows(SecurityException::class.java) {
            Android17Compat.getPackageInfoOrThrow("eu.darken.porter", 0, 0)
        }
    }

    companion object {
        fun set(name: String, value: Any?) {
            val field = Android17Compat::class.java.getDeclaredField(name)
            field.isAccessible = true
            field.set(null, value)
        }
    }
}
