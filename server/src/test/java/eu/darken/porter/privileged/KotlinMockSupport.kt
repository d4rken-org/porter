package eu.darken.porter.privileged

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Bundle
import org.mockito.ArgumentMatchers

/*
 * Mockito matchers for a Kotlin parameter declared non-null: the null a matcher returns is cast
 * rather than checked, so the call site compiles and the matcher still registers.
 */

@Suppress("UNCHECKED_CAST")
internal fun <T> anyK(): T = ArgumentMatchers.any<T>() ?: (null as T)

@Suppress("UNCHECKED_CAST")
internal fun <T> anyK(type: Class<T>): T = ArgumentMatchers.any(type) ?: (null as T)

@Suppress("UNCHECKED_CAST")
internal fun <T> eqK(value: T): T = ArgumentMatchers.eq(value) ?: (null as T)

/** An installed package the way the discovery and history tests describe one. */
internal fun installedApp(name: String, uid: Int, vararg permissions: String): PackageInfo {
    val info = PackageInfo()
    info.packageName = name
    info.firstInstallTime = 100
    info.requestedPermissions = if (permissions.isEmpty()) null else arrayOf(*permissions)
    info.applicationInfo = ApplicationInfo().also {
        it.packageName = name
        it.uid = uid
        it.metaData = Bundle().apply { putBoolean("moe.shizuku.client.V3_SUPPORT", true) }
    }
    return info
}
