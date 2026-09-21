package eu.darken.porter.privileged

import android.content.Context
import android.content.pm.PackageManager
import java.lang.reflect.Proxy

internal object PermissionObserver {

    @Throws(ReflectiveOperationException::class)
    fun register(changed: (Int) -> Unit) {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        var thread = activityThreadClass.getMethod("currentActivityThread").invoke(null)
        if (thread == null) thread = activityThreadClass.getMethod("systemMain").invoke(null)
        val context = activityThreadClass.getMethod("getSystemContext").invoke(thread) as Context
        val listenerClass = Class.forName("android.content.pm.PackageManager\$OnPermissionsChangedListener")
        val listener = Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass)) { proxy, method, args ->
            when (method.name) {
                "onPermissionsChanged" -> {
                    changed(args[0] as Int)
                    null
                }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args[0]
                "toString" -> "Porter permission observer"
                else -> null
            }
        }
        PackageManager::class.java.getMethod("addOnPermissionsChangeListener", listenerClass)
            .invoke(context.packageManager, listener)
    }
}
