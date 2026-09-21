package eu.darken.porter.privileged.util

import android.content.pm.UserInfo
import android.os.IBinder
import android.os.ServiceManager
import android.util.Log
import java.lang.reflect.InvocationTargetException
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
        sActivityManager?.let { return it }
        val resolved = try {
            val binder = ServiceManager.getService("activity")
            val stubClass = Class.forName("android.app.IActivityManager\$Stub")
            stubClass.getDeclaredMethod("asInterface", IBinder::class.java).invoke(null, binder)
        } catch (e: ClassNotFoundException) {
            // Before Oreo the interface had no Stub; the singleton was reached this way instead.
            Class.forName("android.app.ActivityManagerNative").getDeclaredMethod("getDefault").invoke(null)
        }
        sActivityManager = checkNotNull(resolved) { "No activity manager" }
        return resolved
    }

    @Synchronized
    @Throws(Exception::class)
    private fun getCurrentUserMethod(): Method {
        sGetCurrentUserMethod?.let { return it }
        // Resolved on the interface, not on the object's own class: what asInterface returns is
        // the AIDL proxy, which is not a public class on every release, and a Method declared by
        // one cannot be invoked from here whatever the hidden-API rules allow.
        val method = Class.forName("android.app.IActivityManager").getMethod("getCurrentUser")
        sGetCurrentUserMethod = method
        return method
    }

    @Synchronized
    private fun forget() {
        sActivityManager = null
    }

    /**
     * The user id on screen, or null when it could not be read. Null is not user 0: a caller that
     * cannot tell should carry on rather than act on a guess.
     */
    fun id(): Int? {
        // Twice at most: a system_server restart leaves a dead proxy cached, and a caller that
        // never dropped it would answer null for the rest of this process's life.
        repeat(2) { attempt ->
            try {
                val manager = getActivityManager()
                return (getCurrentUserMethod().invoke(manager) as? UserInfo)?.id
            } catch (e: Throwable) {
                val cause = (e as? InvocationTargetException)?.targetException ?: e
                if (attempt == 0) {
                    forget()
                } else {
                    Log.w(TAG, "Cannot read the current user", cause)
                }
            }
        }
        return null
    }
}
