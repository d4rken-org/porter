package eu.darken.porter.manager.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import eu.darken.porter.manager.AppConstants
import eu.darken.porter.manager.MainActivity
import eu.darken.porter.manager.NotificationChannels
import eu.darken.porter.manager.R
import eu.darken.porter.manager.PorterSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

abstract class AuthenticatedReceiver : BroadcastReceiver() {

    companion object {
        private const val NOTIFICATION_ID = 1450
        private val INVALID_TOKEN_NOTIFICATION_INTERVAL = TimeUnit.MINUTES.toMillis(5)

        // The receivers are exported without a permission, so any app can broadcast to them;
        // one stale-token notification per interval is enough to tell a user their automation
        // needs a new token, and the rest only gets logged. onReceive runs on the main thread.
        private var invalidTokenNotifiedAt: Long? = null

        @VisibleForTesting
        internal fun resetForTest() {
            invalidTokenNotifiedAt = null
        }
    }

    final override fun onReceive(context: Context, intent: Intent) {
        val authToken = intent.getStringExtra("auth")
        if (authToken.isNullOrEmpty()) {
            Log.w(AppConstants.TAG, "Ignoring ${intent.action}: no auth token")
            return
        }
        if (authToken != PorterSettings.authToken) {
            Log.w(AppConstants.TAG, "Ignoring ${intent.action}: wrong auth token")
            val now = SystemClock.elapsedRealtime()
            val last = invalidTokenNotifiedAt
            if (last == null || now - last >= INVALID_TOKEN_NOTIFICATION_INTERVAL) {
                invalidTokenNotifiedAt = now
                context.notifyInvalidToken()
            }
            return
        }
        // A root start blocks on the su shell; that must not happen on the receiver's main thread.
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                onAuthenticated(context, intent)
            } finally {
                result.finish()
            }
        }
    }

    private fun Context.notifyInvalidToken() {
        val channel = NotificationChannel(
            NotificationChannels.AUTH,
            getString(R.string.notification_channel_auth),
            NotificationManager.IMPORTANCE_HIGH
        )

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or 
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        val launchPendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, NotificationChannels.AUTH)
            .setContentTitle(getString(R.string.notification_auth_invalid_title))
            .setContentText(getString(R.string.notification_auth_invalid_message))
            .setContentIntent(launchPendingIntent)
            .setAutoCancel(true)
            .setSmallIcon(R.drawable.ic_system_icon)
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }

    abstract fun onAuthenticated(context: Context, intent: Intent)
}
