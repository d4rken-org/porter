package eu.darken.porter.manager.utils

import android.app.NotificationManager
import android.content.Context

/**
 * Whether a notification this app posts while the user is elsewhere would actually be shown.
 *
 * `areNotificationsEnabled` answers for the app as a whole, and from API 33 that includes the
 * `POST_NOTIFICATIONS` grant. A channel the user muted on its own blocks only its own messages,
 * and a channel that has not been created yet cannot be muted.
 */
object NotificationAlerts {

    fun canAlert(context: Context, channelId: String): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return false
        val channel = manager.getNotificationChannel(channelId)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }
}
