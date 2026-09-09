package moe.shizuku.manager.adb

import android.annotation.TargetApi
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.Observer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.R
import moe.shizuku.manager.home.HomeActivity
import rikka.core.ktx.unsafeLazy

@TargetApi(Build.VERSION_CODES.R)
class AdbPairingService : Service() {

    companion object {

        const val NOTIFICATION_CHANNEL = "adb_pairing"
        const val NOTIFICATION_ID = 1

        private const val tag = "AdbPairingService"

        private const val replyRequestId = 1
        private const val stopRequestId = 2
        private const val retryRequestId = 3
        private const val launchRequestId = 4
        private const val startRequestId = 5
        private const val startAction = "start"
        private const val stopAction = "stop"
        private const val replyAction = "reply"
        private const val remoteInputResultKey = "paring_code"
        private const val portKey = "paring_code"
        private const val hostKey = "pairing_host"

        fun startIntent(context: Context): Intent {
            return Intent(context, AdbPairingService::class.java).setAction(startAction)
        }

        private fun stopIntent(context: Context): Intent {
            return Intent(context, AdbPairingService::class.java).setAction(stopAction)
        }

        private fun replyIntent(context: Context, host: String, port: Int): Intent {
            return Intent(context, AdbPairingService::class.java).setAction(replyAction)
                .putExtra(hostKey, host).putExtra(portKey, port)
        }
    }

    private var adbMdns: AdbMdns? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var attempt: Job? = null
    private var latestStartId = 0
    internal var pair: suspend (String, Int, String) -> Unit = ::pairAdb

    private val notifications get() = getSystemService(NotificationManager::class.java)

    private val observer = Observer<Pair<String, Int>> { (host, port) ->
        if (adbMdns == null) return@Observer
        invalidateReplyAction()
        notifications.notify(NOTIFICATION_ID,
            if (port > 0) createInputNotification(host, port) else searchingNotification)
    }

    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannel(
            NotificationChannel(NOTIFICATION_CHANNEL,
                getString(R.string.notification_channel_adb_pairing), NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setSound(null, null)
                setShowBadge(false)
                setAllowBubbles(false)
            })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        when (intent?.action) {
            startAction -> {
                reset()
                notifications.cancel(NOTIFICATION_ID)
                if (foreground(searchingNotification)) startSearch()
            }
            replyAction -> {
                reset()
                if (foreground(workingNotification)) {
                    val code = RemoteInput.getResultsFromIntent(intent)
                        ?.getCharSequence(remoteInputResultKey)?.toString().orEmpty().trim()
                    val host = intent.getStringExtra(hostKey) ?: "127.0.0.1"
                    val port = intent.getIntExtra(portKey, -1)
                    attempt = scope.launch {
                        try {
                            if (port !in 1..65535 || !code.matches(Regex("[0-9]{6}"))) {
                                throw AdbInvalidPairingCodeException()
                            }
                            pair(host, port, code)
                            coroutineContext.ensureActive()
                            handleResult(true, null)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            coroutineContext.ensureActive()
                            Log.w(tag, "Pair failed", e)
                            handleResult(false, pairingFailureMessage(e))
                        }
                    }
                }
            }
            stopAction -> {
                reset()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> stopSelf()
        }
        // A remote reply contains a single-use code; never replay it after process death.
        return START_NOT_STICKY
    }

    private fun foreground(notification: Notification): Boolean = try {
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST)
        true
    } catch (e: Exception) {
        Log.e(tag, "startForeground failed", e)
        handleResult(false, getString(R.string.porter_pairing_failed_retry))
        false
    }

    override fun onTimeout(startId: Int) {
        reset()
        handleResult(false, getString(R.string.porter_pairing_notification_timeout))
    }

    private fun startSearch() {
        val discovery = AdbMdns(this, AdbMdns.TLS_PAIRING, observer)
        adbMdns = discovery
        discovery.start()
    }

    private fun reset() {
        attempt?.cancel()
        attempt = null
        val discovery = adbMdns
        adbMdns = null
        discovery?.stop()
        invalidateReplyAction()
    }

    override fun onDestroy() {
        reset()
        scope.cancel()
        super.onDestroy()
    }

    private fun handleResult(success: Boolean, message: String?) {
        stopForeground(STOP_FOREGROUND_DETACH)
        notifications.notify(NOTIFICATION_ID,
            Notification.Builder(this, NOTIFICATION_CHANNEL)
                .setColor(getColor(R.color.notification))
                .setSmallIcon(R.drawable.ic_system_icon)
                .setContentTitle(getString(if (success) R.string.notification_adb_pairing_succeed_title
                    else R.string.notification_adb_pairing_failed_title))
                .setContentText(message ?: getString(R.string.notification_adb_pairing_succeed_text))
                .setStyle(Notification.BigTextStyle().bigText(message
                    ?: getString(R.string.notification_adb_pairing_succeed_text)))
                .setContentIntent(if (success) launchPendingIntent else retryTutorialPendingIntent)
                .addAction(if (success) startNotificationAction else retryNotificationAction)
                .setAutoCancel(true)
                .build())
        stopSelf(latestStartId)
    }

    private val retryTutorialPendingIntent by unsafeLazy {
        PendingIntent.getActivity(this, retryRequestId, Intent(this, AdbPairingTutorialActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private val launchIntent by unsafeLazy {
        Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or 
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
    }

    private val launchPendingIntent by unsafeLazy {
        PendingIntent.getActivity(
            this, launchRequestId, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private val startNotificationAction by unsafeLazy {
        val startIntent = Intent(launchIntent)
            .putExtra(HomeActivity.EXTRA_START_SERVICE_VIA_WADB, true)

        val pendingIntent = PendingIntent.getActivity(
            this, startRequestId, startIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        Notification.Action.Builder(
            null,
            getString(R.string.home_root_button_start),
            pendingIntent
        )
            .build()
    }

    private val stopNotificationAction by unsafeLazy {
        val pendingIntent = PendingIntent.getService(
            this,
            stopRequestId,
            stopIntent(this),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_IMMUTABLE
            else
                0
        )

        Notification.Action.Builder(
            null,
            getString(R.string.notification_adb_pairing_stop_searching),
            pendingIntent
        )
            .build()
    }

    private val retryNotificationAction by unsafeLazy {
        val pendingIntent = PendingIntent.getForegroundService(
            this,
            retryRequestId,
            startIntent(this),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_IMMUTABLE
            else
                0
        )

        Notification.Action.Builder(
            null,
            getString(R.string.notification_adb_pairing_retry),
            pendingIntent
        )
            .build()
    }

    private fun invalidateReplyAction() {
        PendingIntent.getForegroundService(this, replyRequestId, replyIntent(this, "", -1),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_MUTABLE)?.cancel()
    }

    private fun replyNotificationAction(host: String, port: Int): Notification.Action {
        val pendingIntent = PendingIntent.getForegroundService(this, replyRequestId,
            replyIntent(this, host, port), PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE)
        return Notification.Action.Builder(null,
            getString(R.string.notification_adb_pairing_input_paring_code), pendingIntent)
            .addRemoteInput(RemoteInput.Builder(remoteInputResultKey)
                .setLabel(getString(R.string.dialog_adb_pairing_paring_code)).build())
            .build()
    }

    private val searchingNotification by unsafeLazy {
        Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setColor(getColor(R.color.notification))
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentTitle(getString(R.string.notification_adb_pairing_searching_for_service_title))
            .addAction(stopNotificationAction)
            .build()
    }

    private fun createInputNotification(host: String, port: Int): Notification {
        return Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setColor(getColor(R.color.notification))
            .setContentTitle(getString(R.string.notification_adb_pairing_service_found_title))
            .setSmallIcon(R.drawable.ic_system_icon)
            .addAction(replyNotificationAction(host, port))
            .addAction(stopNotificationAction)
            .build()
    }

    private val workingNotification by unsafeLazy {
        Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setColor(getColor(R.color.notification))
            .setContentTitle(getString(R.string.notification_adb_pairing_working_title))
            .addAction(stopNotificationAction)
            .setSmallIcon(R.drawable.ic_system_icon)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
