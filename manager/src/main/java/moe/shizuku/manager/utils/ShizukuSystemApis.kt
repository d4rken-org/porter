package moe.shizuku.manager.utils

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.RemoteException
import rikka.hidden.compat.PermissionManagerApis
import rikka.hidden.compat.UserManagerApis
import rikka.hidden.compat.util.SystemServiceBinder
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.server.util.InstalledPackagesCompat

private fun loadUsersFromService(): List<UserInfoCompat> {
    return if (!ShizukuStateMachine.instance.isRunning()) {
        arrayListOf(UserInfoCompat(UserHandleCompat.myUserId(), "Owner"))
    } else try {
        val list = UserManagerApis.getUsers(true, true, true)
        val users: MutableList<UserInfoCompat> = ArrayList<UserInfoCompat>()
        for (ui in list) {
            users.add(UserInfoCompat(ui.id, ui.name))
        }
        users
    } catch (tr: Throwable) {
        arrayListOf(UserInfoCompat(UserHandleCompat.myUserId(), "Owner"))
    }
}

class ShizukuSystemApis internal constructor(
    private val loadUsers: () -> List<UserInfoCompat> = ::loadUsersFromService,
    private val installBinderListener: (SystemServiceBinder.OnGetBinderListener) -> Unit =
        SystemServiceBinder<*>::setOnGetBinderListener,
) {

    /** Set after the registration returns: until then calls reach the system unwrapped. */
    @Volatile
    private var ready = false

    private val users = arrayListOf<UserInfoCompat>()

    /**
     * Routes every system service binder through Shizuku. Separate from construction, and taken
     * once: the registration is a process-wide single slot, so a second one would only overwrite
     * what the first installed.
     */
    @Synchronized
    fun attachToSystemServices() {
        if (ready) return
        installBinderListener { ShizukuBinderWrapper(it) }
        ready = true
    }

    fun getUsers(useCache: Boolean = true): List<UserInfoCompat> {
        checkReady()
        synchronized(users) {
            if (!useCache || users.isEmpty()) {
                users.clear()
                users.addAll(loadUsers())
            }
            return users
        }
    }

    fun getUserInfo(userId: Int): UserInfoCompat {
        return getUsers(useCache = true).firstOrNull { it.id == userId } ?: UserInfoCompat(
            UserHandleCompat.myUserId(),
            "Unknown"
        )
    }

    fun getInstalledPackages(flags: Long, userId: Int): List<PackageInfo> {
        checkReady()
        return if (!ShizukuStateMachine.instance.isRunning()) {
            ArrayList()
        } else try {
            InstalledPackagesCompat.getInstalledPackages(flags, userId)
        } catch (tr: RemoteException) {
            throw RuntimeException(tr.message, tr)
        } catch (tr: ReflectiveOperationException) {
            throw RuntimeException(tr.message, tr)
        }
    }

    fun checkPermission(permName: String, pkgName: String, userId: Int): Int {
        checkReady()
        return if (!ShizukuStateMachine.instance.isRunning()) {
            PackageManager.PERMISSION_DENIED
        } else try {
            PermissionManagerApis.checkPermission(permName, pkgName, userId)
        } catch (tr: RemoteException) {
            throw RuntimeException(tr.message, tr)
        }
    }

    fun grantRuntimePermission(packageName: String, permissionName: String, userId: Int) {
        checkReady()
        if (!ShizukuStateMachine.instance.isRunning()) {
            return
        }
        try {
            PermissionManagerApis.grantRuntimePermission(packageName, permissionName, userId)
        } catch (tr: RemoteException) {
            throw RuntimeException(tr.message, tr)
        }
    }

    fun revokeRuntimePermission(packageName: String, permissionName: String, userId: Int) {
        checkReady()
        if (!ShizukuStateMachine.instance.isRunning()) {
            return
        }
        try {
            PermissionManagerApis.revokeRuntimePermission(packageName, permissionName, userId)
        } catch (tr: RemoteException) {
            throw RuntimeException(tr.message, tr)
        }
    }

    /**
     * Without the registration these calls still reach the system, as this process rather than as
     * the service, and answer wrongly instead of failing.
     */
    private fun checkReady() = check(ready) { "ShizukuSystemApis used before attachToSystemServices()" }

    companion object {
        @Volatile
        private var _instance: ShizukuSystemApis? = null

        val instance: ShizukuSystemApis
            get() = _instance ?: synchronized(this) {
                _instance ?: ShizukuSystemApis().also { _instance = it }
            }

        internal fun resetForTest() {
            _instance = null
        }
    }
}
