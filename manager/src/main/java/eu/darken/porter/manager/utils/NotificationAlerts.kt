package eu.darken.porter.manager.utils

import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * Whether a notification this app posts while the user is elsewhere would actually be shown.
 *
 * `areNotificationsEnabled` answers for the app as a whole, and from API 33 that includes the
 * `POST_NOTIFICATIONS` grant. A channel the user muted on its own blocks only its own messages,
 * and a channel that has not been created yet cannot be muted.
 *
 * Neither check covers a blocked channel group, nor Do Not Disturb, nor a foreground notification
 * the system defers. This answers whether the app and the channel are muted, which is what the
 * callers ask it.
 */
object NotificationAlerts {

    fun canAlert(context: Context, channelId: String): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return false
        // Channels arrived in Oreo and this app supports Nougat, where the app-wide answer is the
        // whole answer and asking for a channel throws.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val channel = manager.getNotificationChannel(channelId)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }
}
