package eu.darken.porter.manager

/** Channel ids persist in the user's notification settings, so these never change once shipped. */
object NotificationChannels {
    const val WATCHDOG = "porter.watchdog"
    const val CRASH = "porter.crash"
    const val AUTH = "porter.auth"
    const val ADB_START = "porter.adb_start"
    const val ADB_PAIRING = "porter.adb_pairing"
}
