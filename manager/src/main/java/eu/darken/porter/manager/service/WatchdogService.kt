package eu.darken.porter.manager.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import eu.darken.porter.manager.Helps
import eu.darken.porter.manager.R
import eu.darken.porter.manager.MainActivity
import eu.darken.porter.manager.NotificationChannels
import eu.darken.porter.manager.PorterSettings
import eu.darken.porter.manager.receiver.PorterReceiverStarter
import eu.darken.porter.manager.starter.ServiceReplacement
import eu.darken.porter.manager.utils.SettingsPage
import eu.darken.porter.manager.utils.PorterStateMachine
import java.util.concurrent.atomic.AtomicBoolean

class WatchdogService : Service() {

    private val environment: WatchdogEnvironment get() = environmentOverride ?: DefaultWatchdogEnvironment(this)

    private var scope: CoroutineScope? = null

    override fun onCreate() {
        super.onCreate()
        isRunning.set(true)
        // Not Main.immediate: this body must not run inline inside the emit that delivers the
        // state, because restartService can block on a root shell. Pinned by
        // WatchdogServiceTest.aCrashReactionIsPostedRatherThanRunInsideTheTransition.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        this.scope = scope
        // UNDISPATCHED so the subscription exists before onCreate returns. Pinned by
        // WatchdogServiceTest.aCrashSupersededBeforeTheFirstIdleIsStillHandled.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            PorterStateMachine.instance.asFlow().collect { state ->
                runCatching {
                    if (state == PorterStateMachine.State.CRASHED && !environment.replacementRunning) {
                        showCrashNotification()
                        environment.restartService(applicationContext)
                    }
                }.onFailure {
                    // Contained: an escaping throw would cancel this collector for the remaining
                    // life of the service, and reach the process-wide uncaught handler.
                    Log.w(TAG, "watchdog reaction failed", it)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "ACTION_STOP_SERVICE") {
            // Only the notification's stop action turns the watchdog off; onDestroy cannot tell
            // a user stop from a low-memory kill or an app update.
            PorterSettings.setWatchdogPreference(false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID_WATCHDOG,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(
                NOTIFICATION_ID_WATCHDOG,
                buildNotification()
            )
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        isRunning.set(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val channel = NotificationChannel(
            NotificationChannels.WATCHDOG,
            getString(R.string.notification_channel_watchdog),
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)

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

        val stopIntent = Intent(this, WatchdogService::class.java).apply {
            action = "ACTION_STOP_SERVICE"
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NotificationChannels.WATCHDOG)
            .setContentTitle(getString(R.string.watchdog_running))
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentIntent(launchPendingIntent)
            .addAction(
                R.drawable.ic_close_24,
                getString(R.string.watchdog_turn_off),
                stopPendingIntent
            )
            .setOngoing(true)
            .build()
    }

    private fun showCrashNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            NotificationChannels.CRASH,
            getString(R.string.notification_channel_crash),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        nm.createNotificationChannel(channel)

        val learnMoreIntent = Intent(Intent.ACTION_VIEW).apply {
            setData(Uri.parse(Helps.STOPPING))
        }
        val learnMorePendingIntent = PendingIntent.getActivity(this, 0, learnMoreIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val disableIntent = SettingsPage.Notifications.NotificationChannel.buildIntent(applicationContext)
        val disablePendingIntent = PendingIntent.getActivity(this, 0, disableIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val notification = NotificationCompat.Builder(this, NotificationChannels.CRASH)
            .setContentTitle(getString(R.string.watchdog_shizuku_crashed_title))
            .setContentText(getString(R.string.watchdog_shizuku_crashed_text))
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentIntent(learnMorePendingIntent)
            .setAutoCancel(true)
            .addAction(0, getString(R.string.watchdog_shizuku_crashed_action_turn_off_alerts), disablePendingIntent)
            .build()

        nm.notify(NOTIFICATION_ID_CRASH, notification)
    }

    companion object {
        private const val TAG = "ShizukuWatchdog"
        private const val NOTIFICATION_ID_WATCHDOG = 1001
        private const val NOTIFICATION_ID_CRASH = 1002

        private val isRunning = AtomicBoolean(false)

        /** Test seam for [WatchdogEnvironment]; production leaves it null. */
        internal var environmentOverride: WatchdogEnvironment? = null

        @JvmStatic
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, WatchdogService::class.java))
            } catch (e: Exception) {
                Log.e("PorterApplication", "Failed to start WatchdogService: ${e.message}" )
            }
        }

        @JvmStatic
        fun stop(context: Context) {
            context.stopService(Intent(context, WatchdogService::class.java))
        }

        @JvmStatic
        fun isRunning(): Boolean = isRunning.get()
    }
}

/** What the watchdog's reaction reaches outside the service. */
internal interface WatchdogEnvironment {
    val replacementRunning: Boolean
    fun restartService(context: Context)
}

internal class DefaultWatchdogEnvironment(private val context: Context) : WatchdogEnvironment {
    override val replacementRunning get() = ServiceReplacement.get(context).state.value.running
    override fun restartService(context: Context) = PorterReceiverStarter.start(context)
}
