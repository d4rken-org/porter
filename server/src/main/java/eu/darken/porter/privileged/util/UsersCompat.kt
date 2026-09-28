package eu.darken.porter.privileged.util

import android.content.Context
import android.content.pm.UserInfo
import android.os.IUserManager
import android.os.RemoteException
import android.util.Log
import rikka.hidden.compat.UserManagerApis
import rikka.hidden.compat.util.SystemServiceBinder

object UsersCompat {

    private const val TAG = "UsersCompat"

    private val userManager = SystemServiceBinder<IUserManager>(Context.USER_SERVICE) { IUserManager.Stub.asInterface(it) }

    /** Android 17 QPR1's IUserManager has only `getUsers(boolean excludeDying)`. */
    @Throws(RemoteException::class)
    fun getUsers(
        excludePartial: Boolean,
        excludeDying: Boolean,
        excludePreCreated: Boolean,
        getUsersExcludingDying: (Boolean) -> List<UserInfo> = { userManager.get().getUsers(it) },
    ): List<UserInfo> {
        try {
            return UserManagerApis.getUsers(excludePartial, excludeDying, excludePreCreated)
        } catch (e: NoSuchMethodError) {
            return getUsersExcludingDying(excludeDying)
        }
    }

    /** Every user's id, or only user 0 when the users cannot be read. */
    fun getUserIdsNoThrow(): Collection<Int> = try {
        getUsers(excludePartial = true, excludeDying = true, excludePreCreated = true).mapTo(LinkedHashSet()) { it.id }
    } catch (tr: Throwable) {
        Log.w(TAG, "Cannot enumerate users, narrowing to user 0", tr)
        setOf(0)
    }
}
