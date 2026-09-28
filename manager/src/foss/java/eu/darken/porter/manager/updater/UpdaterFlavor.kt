package eu.darken.porter.manager.updater

import android.content.Context

object UpdaterFlavor {
    fun checker(context: Context): UpdateChecker = GithubUpdateChecker(context.applicationContext)
}
