package moe.shizuku.manager.utils

import android.os.Binder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import rikka.hidden.compat.util.SystemServiceBinder
import rikka.shizuku.ShizukuBinderWrapper

/**
 * Routing the system services through Shizuku is a step of its own, taken once and separate from
 * construction.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShizukuSystemApisTest {

    private val installed = mutableListOf<SystemServiceBinder.OnGetBinderListener>()
    private var loads = 0

    private fun newApis(
        install: (SystemServiceBinder.OnGetBinderListener) -> Unit = { installed.add(it) },
    ) = ShizukuSystemApis(
        loadUsers = { loads++; listOf(UserInfoCompat(0, "Owner"), UserInfoCompat(10, "Work")) },
        installBinderListener = install,
    )

    @Before fun stopTheService() {
        ShizukuStateMachine.instance.set(ShizukuStateMachine.State.STOPPED)
    }

    @After fun dropTheInstance() {
        ShizukuSystemApis.resetForTest()
        ShizukuStateMachine.instance.set(ShizukuStateMachine.State.STOPPED)
    }

    @Test fun constructingRegistersNoBinderListener() {
        newApis()

        assertEquals(emptyList<SystemServiceBinder.OnGetBinderListener>(), installed)
    }

    @Test fun attachingInstallsAListenerThatWrapsTheBinder() {
        newApis().attachToSystemServices()

        assertEquals(1, installed.size)
        assertTrue(
            "the installed listener did not wrap the binder, so hidden API calls would reach the " +
                "system as this process rather than as the service",
            installed.single().onGetBinder(Binder()) is ShizukuBinderWrapper,
        )
    }

    @Test fun attachingTwiceInstallsOneListener() {
        val apis = newApis()

        apis.attachToSystemServices()
        apis.attachToSystemServices()

        assertEquals(1, installed.size)
    }

    @Test fun anApiCallMadeWhileRegisteringIsRefused() {
        var failure: Throwable? = null
        lateinit var apis: ShizukuSystemApis
        apis = newApis(install = { failure = runCatching { apis.getUsers() }.exceptionOrNull() })

        apis.attachToSystemServices()

        assertTrue(
            "a call made while the registration was still in flight was answered, so readiness is " +
                "published before the wrapping is installed, found: $failure",
            failure is IllegalStateException,
        )
        assertEquals(0, loads)
    }

    @Test fun aReadInProgressSurvivesTheCacheBeingRefilled() {
        val apis = newApis().apply { attachToSystemServices() }

        val iterator = apis.getUsers().iterator()
        apis.getUsers(useCache = false)

        assertEquals(listOf(0, 10), iterator.asSequence().map { it.id }.toList())
    }

    @Test fun onlyAnUncachedReadReachesTheLoaderAgain() {
        val apis = newApis().apply { attachToSystemServices() }

        apis.getUsers()
        assertEquals(1, loads)
        apis.getUsers(useCache = true)
        assertEquals(1, loads)
        apis.getUsers(useCache = false)
        assertEquals(2, loads)
    }

    @Test fun withoutARunningServiceTheUsersAreThisUserAlone() {
        val apis = ShizukuSystemApis(installBinderListener = { installed.add(it) })
        apis.attachToSystemServices()

        val users = apis.getUsers()

        assertEquals(1, users.size)
        assertEquals(UserHandleCompat.myUserId(), users.single().id)
        assertEquals("Owner", users.single().name)
    }
}
