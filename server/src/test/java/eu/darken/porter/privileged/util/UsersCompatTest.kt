package eu.darken.porter.privileged.util

import android.content.pm.UserInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import rikka.hidden.compat.UserManagerApis

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class UsersCompatTest {

    private lateinit var users: MockedStatic<UserManagerApis>

    @Before
    fun setup() {
        users = Mockito.mockStatic(UserManagerApis::class.java)
    }

    @After
    fun teardown() {
        users.close()
    }

    private fun threeFlagCallThrows(failure: Throwable) {
        users.`when`<List<UserInfo>> { UserManagerApis.getUsers(anyBoolean(), anyBoolean(), anyBoolean()) }
            .thenThrow(failure)
    }

    @Test
    fun `the three-flag call answers where it exists`() {
        users.`when`<List<UserInfo>> { UserManagerApis.getUsers(true, true, true) }.thenReturn(userInfos(0, 10))

        val ids = UsersCompat.getUsers(true, true, true) { error("the fallback must not run") }.map { it.id }

        assertEquals(listOf(0, 10), ids)
    }

    @Test
    fun `Android 17 falls back to the excludeDying overload`() {
        threeFlagCallThrows(NoSuchMethodError("getUsers(ZZZ)"))

        var asked: Boolean? = null

        val ids = UsersCompat.getUsers(true, false, true) { excludeDying ->
            asked = excludeDying
            userInfos(0, 10)
        }.map { it.id }

        assertEquals(listOf(0, 10), ids)
        assertEquals(false, asked)
    }

    @Test
    fun `users that cannot be read narrow to user 0`() {
        threeFlagCallThrows(IllegalStateException("user service unavailable"))

        assertEquals(setOf(0), UsersCompat.getUserIdsNoThrow())
    }
}
