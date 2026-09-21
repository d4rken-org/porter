package eu.darken.porter.privileged.util

import android.content.pm.UserInfo
import android.os.IBinder
import android.os.ServiceManager
import android.util.Log
import java.lang.reflect.Method

/** Which Android user's screen the device is showing, which decides what an activity launch can reach. */
object ForegroundUser {

    private const val TAG = "PorterForegroundUser"

    // Static fields of this class, by these names: the tests reach them through reflection.
    @Volatile
    private var sActivityManager: Any? = null

    @Volatile
    private var sGetCurrentUserMethod: Method? = null

    @Synchronized
    @Throws(Exception::class)
    private fun getActivityManager(): Any {
        if (sActivityManager == null) {
            val binder = ServiceManager.getService("activity")
            val stubClass = Class.forName("android.app.IActivityManager\$Stub")
            sActivityManager = stubClass.getDeclaredMethod("asInterface", IBinder::class.java).invoke(null, binder)
        }
        return sActivityManager!!
    }

    @Synchronized
    @Throws(Exception::class)
    private fun getCurrentUserMethod(manager: Any): Method {
        if (sGetCurrentUserMethod == null) {
            sGetCurrentUserMethod = manager.javaClass.getMethod("getCurrentUser")
        }
        return sGetCurrentUserMethod!!
    }

    /**
     * The user id on screen, or null when it could not be read. Null is not user 0: a caller that
     * cannot tell should carry on rather than act on a guess.
     */
    fun id(): Int? = try {
        val manager = getActivityManager()
        (getCurrentUserMethod(manager).invoke(manager) as? UserInfo)?.id
    } catch (e: Throwable) {
        Log.w(TAG, "Cannot read the current user", e)
        null
    }
}
