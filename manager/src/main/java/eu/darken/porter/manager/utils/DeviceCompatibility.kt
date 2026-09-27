package eu.darken.porter.manager.utils

import android.os.Build
import android.os.SystemProperties
import android.text.TextUtils

object DeviceCompatibility {

    val isMiui: Boolean by lazy { !TextUtils.isEmpty(SystemProperties.get("ro.miui.ui.version.name")) }

    /** MIUI or HyperOS, not merely Xiaomi hardware running another ROM. */
    fun isXiaomiRom(): Boolean = isXiaomiRom(Build.MANUFACTURER, Build.VERSION.INCREMENTAL)

    internal fun isXiaomiRom(manufacturer: String, incremental: String): Boolean =
        manufacturer.lowercase() in setOf("xiaomi", "poco", "blackshark") && XIAOMI_INCREMENTAL.containsMatchIn(incremental)

    // V11.0.8.0.PCBMIXM (MIUI 11), V816.0.1.0.UMNMIXM (HyperOS 1), OS2.0.6.0.VMLMIXM (HyperOS 2)
    private val XIAOMI_INCREMENTAL = Regex("^(?:V[0-9]+|OS[0-9]+)\\.")
}
