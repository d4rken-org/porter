package eu.darken.porter.manager

import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat

/** Channel ids persist in the user's notification settings, so these never change once shipped. */
object NotificationChannels {
    const val WATCHDOG = "porter.watchdog"
    const val CRASH = "porter.crash"
    const val AUTH = "porter.auth"
    const val ADB_START = "porter.adb_start"
    const val ADB_PAIRING = "porter.adb_pairing"

    /** Does nothing before Oreo, which has no channels. */
    fun create(context: Context, id: String, name: CharSequence, importance: Int) {
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(id, importance).setName(name).build()
        )
    }
}
