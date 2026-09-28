package eu.darken.porter.manager.updater

import android.content.Context

object UpdaterFlavor {
    @Suppress("UNUSED_PARAMETER")
    fun checker(context: Context): UpdateChecker = UnsupportedUpdateChecker
}
