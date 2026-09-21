package eu.darken.porter.manager.utils

import android.os.SystemProperties
import android.text.TextUtils

object DeviceCompatibility {

    val isMiui: Boolean by lazy { !TextUtils.isEmpty(SystemProperties.get("ro.miui.ui.version.name")) }
}
