package eu.darken.porter.common.util

import android.os.SELinux
import android.system.Os

object OsUtils {

    // Static accessors, so a test can stand in for this process's identity with mockStatic.
    @JvmStatic
    val uid: Int = Os.getuid()

    @JvmStatic
    val pid: Int = Os.getpid()

    @JvmStatic
    val seLinuxContext: String? = try {
        SELinux.getContext()
    } catch (tr: Throwable) {
        null
    }
}
