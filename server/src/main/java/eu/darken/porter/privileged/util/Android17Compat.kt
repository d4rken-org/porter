package eu.darken.porter.privileged.util

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.RemoteException
import android.os.ServiceManager
import android.util.Log
import java.lang.reflect.Method
import rikka.hidden.compat.PackageManagerApis
import rikka.hidden.compat.PermissionManagerApis

object Android17Compat {

    private const val TAG = "ShizukuAndroid17Compat"
    private const val DEVICE_ID_DEFAULT = 0

    // Static fields of this class, by these names: the tests reach them through reflection.
    @Volatile
    private var sPackageManager: Any? = null

    @Volatile
    private var sGetPackageInfoMethod: Method? = null

    @Volatile
    private var sGetApplicationInfoMethod: Method? = null

    @Volatile
    private var sPermissionManager: Any? = null

    @Volatile
    private var sGrantRuntimePermissionMethod: Method? = null

    @Volatile
    private var sRevokeRuntimePermissionMethod: Method? = null

    @Volatile
    private var sCheckPermissionMethod: Method? = null

    @Volatile
    private var sCheckPermissionUidMethod: Method? = null

    @Synchronized
    @Throws(Exception::class)
    private fun getPackageManager(): Any {
        if (sPackageManager == null) {
            val binder = ServiceManager.getService("package")
            val stubClass = Class.forName("android.content.pm.IPackageManager\$Stub")
            sPackageManager = stubClass.getDeclaredMethod("asInterface", IBinder::class.java).invoke(null, binder)
        }
        return sPackageManager!!
    }

    @Synchronized
    @Throws(Exception::class)
    private fun getPermissionManager(): Any {
        if (sPermissionManager == null) {
            val binder = ServiceManager.getService("permissionmgr")
            val stubClass = Class.forName("android.permission.IPermissionManager\$Stub")
            sPermissionManager = stubClass.getDeclaredMethod("asInterface", IBinder::class.java).invoke(null, binder)
        }
        return sPermissionManager!!
    }

    /**
     * The package-manager lookup plus the reflection fallback, keeping the difference between a
     * package that is not there and a question that was not answered: `null` means the package
     * manager said no such package, a throw means the call failed. Reconciliation must not read the
     * second as the first.
     *
     * The direct call is the throwing one. The `NoThrow` helpers swallow [Throwable],
     * so a [NoSuchMethodError] never reached the catch below and the fallback was dead code.
     */
    @Throws(RemoteException::class)
    fun getPackageInfoOrThrow(packageName: String, flags: Long, userId: Int): PackageInfo? {
        try {
            return PackageManagerApis.getPackageInfo(packageName, flags, userId)
        } catch (e: NoSuchMethodError) {
            return getPackageInfoByReflection(packageName, flags, userId, e)
        }
    }

    private fun getPackageInfoByReflection(packageName: String, flags: Long, userId: Int, cause: NoSuchMethodError): PackageInfo? {
        try {
            val pm = getPackageManager()
            if (sGetPackageInfoMethod == null) {
                synchronized(this) {
                    if (sGetPackageInfoMethod == null) {
                        sGetPackageInfoMethod = findMethod(pm, "getPackageInfo", String::class.java, java.lang.Long.TYPE)
                    }
                }
            }
            val method = sGetPackageInfoMethod
                ?: throw NoSuchMethodException("getPackageInfo(String, long, ...)")
            return invokeMethod(pm, method, packageName, flags, userId) as PackageInfo?
        } catch (ex: Throwable) {
            val failure = IllegalStateException("Android 17 fallback for getPackageInfo failed", ex)
            failure.addSuppressed(cause)
            throw failure
        }
    }

    fun getPackageInfo(packageName: String, flags: Long, userId: Int): PackageInfo? {
        try {
            return getPackageInfoOrThrow(packageName, flags, userId)
        } catch (e: RemoteException) {
            // PMS parcels SecurityException, IllegalArgumentException and IllegalStateException back,
            // and Parcel.readException rethrows them. Callers here run in the server constructor and
            // in a process-observer callback, neither of which catches.
            Log.e(TAG, "getPackageInfo failed", e)
            return null
        } catch (e: RuntimeException) {
            Log.e(TAG, "getPackageInfo failed", e)
            return null
        }
    }

    fun getApplicationInfo(packageName: String, flags: Long, userId: Int): ApplicationInfo? {
        try {
            return PackageManagerApis.getApplicationInfo(packageName, flags, userId)
        } catch (e: NoSuchMethodError) {
            try {
                val pm = getPackageManager()
                if (sGetApplicationInfoMethod == null) {
                    synchronized(this) {
                        if (sGetApplicationInfoMethod == null) {
                            sGetApplicationInfoMethod = findMethod(pm, "getApplicationInfo", String::class.java, java.lang.Long.TYPE)
                        }
                    }
                }
                val method = sGetApplicationInfoMethod
                if (method != null) {
                    return invokeMethod(pm, method, packageName, flags, userId) as ApplicationInfo?
                }
            } catch (ex: Throwable) {
                Log.e(TAG, "Android 17 fallback for getApplicationInfo failed", ex)
            }
            return null
        } catch (e: RemoteException) {
            Log.e(TAG, "getApplicationInfo failed", e)
            return null
        } catch (e: RuntimeException) {
            Log.e(TAG, "getApplicationInfo failed", e)
            return null
        }
    }

    fun checkPermission(permissionName: String, packageName: String, userId: Int): Int {
        try {
            return PermissionManagerApis.checkPermission(permissionName, packageName, userId)
        } catch (e: NoSuchMethodError) {
            try {
                val pm = getPermissionManager()
                if (sCheckPermissionMethod == null) {
                    synchronized(this) {
                        if (sCheckPermissionMethod == null) {
                            sCheckPermissionMethod = findMethod(pm, "checkPermission", String::class.java, String::class.java)
                        }
                    }
                }
                val method = sCheckPermissionMethod
                if (method != null) {
                    return invokeMethod(pm, method, permissionName, packageName, userId) as Int
                }
            } catch (ex: Throwable) {
                Log.e(TAG, "Android 17 fallback for checkPermission(String, String, int) failed", ex)
            }
            return PackageManager.PERMISSION_DENIED
        } catch (e: RemoteException) {
            return PackageManager.PERMISSION_DENIED
        }
    }

    fun checkPermission(permissionName: String, uid: Int): Int {
        try {
            return PermissionManagerApis.checkPermission(permissionName, uid)
        } catch (e: NoSuchMethodError) {
            try {
                val pm = getPermissionManager()
                if (sCheckPermissionUidMethod == null) {
                    synchronized(this) {
                        if (sCheckPermissionUidMethod == null) {
                            sCheckPermissionUidMethod = findMethod(pm, "checkPermission", String::class.java, Integer.TYPE)
                        }
                    }
                }
                val method = sCheckPermissionUidMethod
                if (method != null) {
                    val paramTypes = method.parameterTypes
                    if (paramTypes.size == 3 && paramTypes[1] == Integer.TYPE && paramTypes[2] == Integer.TYPE) {
                        return method.invoke(pm, permissionName, DEVICE_ID_DEFAULT, uid) as Int
                    }
                    return method.invoke(pm, permissionName, uid) as Int
                }
            } catch (ex: Throwable) {
                Log.e(TAG, "Android 17 fallback for checkPermission(String, int) failed", ex)
            }
            return PackageManager.PERMISSION_DENIED
        } catch (e: RemoteException) {
            return PackageManager.PERMISSION_DENIED
        }
    }

    @Throws(RemoteException::class)
    fun grantRuntimePermission(packageName: String, permissionName: String, userId: Int) {
        try {
            PermissionManagerApis.grantRuntimePermission(packageName, permissionName, userId)
        } catch (e: NoSuchMethodError) {
            try {
                val pm = getPermissionManager()
                if (sGrantRuntimePermissionMethod == null) {
                    synchronized(this) {
                        if (sGrantRuntimePermissionMethod == null) {
                            sGrantRuntimePermissionMethod = findMethod(pm, "grantRuntimePermission", String::class.java, String::class.java)
                        }
                    }
                }
                val method = sGrantRuntimePermissionMethod
                if (method != null) {
                    invokeMethod(pm, method, packageName, permissionName, userId)
                }
            } catch (ex: Throwable) {
                Log.e(TAG, "Android 17 fallback for grantRuntimePermission failed", ex)
            }
        }
    }

    @Throws(RemoteException::class)
    fun revokeRuntimePermission(packageName: String, permissionName: String, userId: Int) {
        try {
            PermissionManagerApis.revokeRuntimePermission(packageName, permissionName, userId)
        } catch (e: NoSuchMethodError) {
            try {
                val pm = getPermissionManager()
                if (sRevokeRuntimePermissionMethod == null) {
                    synchronized(this) {
                        if (sRevokeRuntimePermissionMethod == null) {
                            sRevokeRuntimePermissionMethod = findMethod(pm, "revokeRuntimePermission", String::class.java, String::class.java)
                        }
                    }
                }
                val method = sRevokeRuntimePermissionMethod
                if (method != null) {
                    val paramTypes = method.parameterTypes
                    if (paramTypes.size == 5 && paramTypes[4] == String::class.java) {
                        method.invoke(pm, packageName, permissionName, DEVICE_ID_DEFAULT, userId, "shizuku")
                    } else {
                        invokeMethod(pm, method, packageName, permissionName, userId)
                    }
                }
            } catch (ex: Throwable) {
                Log.e(TAG, "Android 17 fallback for revokeRuntimePermission failed", ex)
            }
        }
    }

    private fun findMethod(obj: Any, name: String, vararg prefixTypes: Class<*>): Method? {
        var bestMethod: Method? = null
        for (method in obj.javaClass.methods) {
            if (name == method.name) {
                val paramTypes = method.parameterTypes
                if (paramTypes.size >= prefixTypes.size) {
                    var match = true
                    for (i in prefixTypes.indices) {
                        if (paramTypes[i] != prefixTypes[i]) {
                            match = false
                            break
                        }
                    }
                    if (match) {
                        if (bestMethod == null || paramTypes.size > bestMethod.parameterTypes.size) {
                            bestMethod = method
                        }
                    }
                }
            }
        }
        return bestMethod
    }

    @Throws(Exception::class)
    private fun invokeMethod(obj: Any, method: Method, vararg prefixArgs: Any?): Any? {
        val paramTypes = method.parameterTypes
        val args = arrayOfNulls<Any>(paramTypes.size)

        val prefixLen = prefixArgs.size - 1
        val userIdIdx = prefixArgs.size - 1
        val userId = prefixArgs[userIdIdx]

        if (paramTypes.size == prefixArgs.size + 1) {
            System.arraycopy(prefixArgs, 0, args, 0, prefixLen)
            args[prefixLen] = DEVICE_ID_DEFAULT
            args[prefixLen + 1] = userId
            for (i in prefixLen + 2 until paramTypes.size) {
                if (paramTypes[i] == Integer.TYPE) args[i] = 0
                else if (paramTypes[i] == String::class.java) args[i] = null
            }
        } else {
            System.arraycopy(prefixArgs, 0, args, 0, minOf(prefixArgs.size, paramTypes.size))
            for (i in prefixArgs.size until paramTypes.size) {
                if (paramTypes[i] == Integer.TYPE) args[i] = userId
            }
        }
        return method.invoke(obj, *args)
    }
}
