package moe.shizuku.manager.ktx

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.UserManager
import moe.shizuku.manager.ShizukuApplication

val Context.application: ShizukuApplication
    get() {
        return applicationContext as ShizukuApplication
    }

fun Context.createDeviceProtectedStorageContextCompat(): Context {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        createDeviceProtectedStorageContext()
    } else {
        this
    }
}

fun Context.createDeviceProtectedStorageContextCompatWhenLocked(): Context {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && getSystemService(UserManager::class.java)?.isUserUnlocked != true) {
        createDeviceProtectedStorageContext()
    } else {
        this
    }
}

inline fun <reified T : Activity> Context.asActivity(): T {
    var context: Context = this
    while (true) {
        if (context is T) return context
        if (context !is ContextWrapper) break
        context = context.baseContext
    }
    throw ClassCastException("Context instance $this is not Activity")
}
