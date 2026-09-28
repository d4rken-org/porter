package eu.darken.porter.manager.updater

import android.content.Context

object UpdaterDebugOverride {
    @Suppress("UNUSED_PARAMETER")
    fun releaseJson(context: Context): String? = null
}
