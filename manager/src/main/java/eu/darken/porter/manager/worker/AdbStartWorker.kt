package eu.darken.porter.manager.worker

import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.lifecycle.asFlow
import androidx.work.*
import java.io.EOFException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import eu.darken.porter.manager.R
import eu.darken.porter.manager.NotificationChannels
import eu.darken.porter.manager.adb.AdbMdns
import eu.darken.porter.manager.adb.AdbPairingRequiredException
import eu.darken.porter.manager.adb.AdbStarter
import eu.darken.porter.manager.home.HomeActivity
import eu.darken.porter.manager.receiver.PorterReceiverStarter
import eu.darken.porter.manager.receiver.PorterReceiverStarter.WorkerState
import eu.darken.porter.manager.receiver.PorterReceiverStarter.updateNotification
import eu.darken.porter.manager.support.SupportActivity
import eu.darken.porter.manager.starter.Starter
import eu.darken.porter.manager.utils.EnvironmentUtils
import eu.darken.porter.manager.utils.PorterStateMachine

class AdbStartWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    /**
     * Set when this run handed the attempt to a replacement, so the cancellation it asked for is
     * not reported as one the user has to wait out. Written from the unlock broadcast on the main
     * thread, read from the coroutine the cancellation lands in.
     */
    @Volatile
    private var handedOff = false

    override suspend fun doWork(): Result {
        try {
            updateNotification(
                applicationContext,
                WorkerState.RUNNING
            )

            val cr = applicationContext.contentResolver

            Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)

            // An adbd already listening on TCP (e.g. set up by a computer or another manager) is used as-is.
            val port = EnvironmentUtils.getAdbTcpPort().takeIf { it > 0 } ?: callbackFlow {
                // The TLS service this discovers is wireless debugging's, which arrived in Android 11.
                // Earlier, discovery can only run into its timeout, so the attempt ends here the same way,
                // after the same request to turn wireless debugging on.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
                    throw TimeoutException("No ADB port, and no wireless debugging to discover one")
                }
                val adbMdns = AdbMdns(applicationContext, AdbMdns.TLS_CONNECT) { p ->
                    if (p.second > 0) trySend(p.second)
                }

                var awaitingAuth = false
                var timeoutJob: Job? = null

                // WorkManager promotes a worker to the foreground and takes it back down only when
                // the run ends, so the wait behind the keyguard is the run: the unlock hands the
                // attempt to a replacement, which repeats the enable and the discovery below
                // without holding a foreground service for the rest of the startup.
                val unlockWaiter = UnlockWaiter(applicationContext) {
                    // An unlock that arrives while this run is already being stopped must not put
                    // the work back: a cancel is one of the things that stops it.
                    if (!isStopped) {
                        handedOff = true
                        enqueue(applicationContext)
                    }
                }

                fun startDiscoveryWithTimeout() {
                    adbMdns.start()
                    timeoutJob?.cancel()
                    timeoutJob = launch {
                        delay(15_000)
                        close(TimeoutException("Timed out during mDNS port discovery"))
                    }
                }

                fun handleAuth() {
                    val km = applicationContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                    // Before the branches: whichever one runs decides what waits next, and the
                    // discovery deadline is not it.
                    timeoutJob?.cancel()
                    if (km.isKeyguardLocked) {
                        // Every further write of the setting lands here again while the keyguard is
                        // up, and one wait is all there is to have.
                        if (unlockWaiter.start()) {
                            val notification = PorterReceiverStarter.buildNotification(
                                applicationContext,
                                null
                            )
                            // The type the manifest declares for WorkManager's own foreground
                            // service. Without it the platform refuses to start the service at all
                            // from an app targeting 35 or later.
                            val foregroundInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                ForegroundInfo(
                                    PorterReceiverStarter.NOTIFICATION_ID,
                                    notification,
                                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                                )
                            } else {
                                ForegroundInfo(
                                    PorterReceiverStarter.NOTIFICATION_ID,
                                    notification
                                )
                            }
                            setForegroundAsync(foregroundInfo)
                        }
                    } else {
                        awaitingAuth = true
                        // With the device already unlocked, the only thing that turns the setting
                        // back on is someone in the wireless-debugging screen at this moment. Where
                        // nobody is, nothing else here can end the wait: discovery has stopped and
                        // its deadline is gone, so the flow would sit in "Starting Porter…" until
                        // something outside it intervened. A later change back on replaces this
                        // deadline with the discovery one.
                        timeoutJob = launch {
                            delay(AUTHORIZATION_TIMEOUT)
                            close(TimeoutException("Timed out waiting for the network to be authorized"))
                        }
                    }
                    adbMdns.stop()
                }

                val observer = object : ContentObserver(null) {
                    override fun onChange(selfChange: Boolean) {
                        when (Settings.Global.getInt(cr, "adb_wifi_enabled", 0)) {
                            0 -> if (awaitingAuth) {
                                close(SecurityException("Network is not authorized for wireless debugging"))
                            } else handleAuth()
                            1 -> startDiscoveryWithTimeout()
                        }
                    }
                }

                Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
                cr.registerContentObserver(Settings.Global.getUriFor("adb_wifi_enabled"), false, observer)
                startDiscoveryWithTimeout()

                awaitClose {
                    adbMdns.stop()
                    timeoutJob?.cancel()
                    cr.unregisterContentObserver(observer)
                    unlockWaiter.stop()
                }
            }.first()
            
            AdbStarter.startAdb(applicationContext, port)
            Starter.waitForBinder()

            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(PorterReceiverStarter.NOTIFICATION_ID)
            nm.cancel(NOTIFICATION_ID)

            return Result.success()
        } catch (e: CancellationException) {
            if (handedOff) {
                // The replacement posts its own state when it starts, but it may sit on an unmet
                // constraint first, and WorkManager takes the foreground notification down with
                // this run. Re-posting it leaves the attempt something to show for itself and the
                // actions to cancel it with.
                updateNotification(applicationContext, WorkerState.RUNNING)
                throw e
            }

            val state = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                WorkerState.AWAITING_RETRY
            } else {
                when (stopReason) {
                    WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY -> WorkerState.AWAITING_WIFI
                    WorkInfo.STOP_REASON_CANCELLED_BY_APP -> WorkerState.STOPPED
                    else -> WorkerState.AWAITING_RETRY
                }
            }
            updateNotification(applicationContext, state)

            throw e
        } catch (e: AdbPairingRequiredException) {
            // No retry can succeed until the user pairs again.
            PorterStateMachine.instance.update()
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(PorterReceiverStarter.NOTIFICATION_ID)
            showPairingRequiredNotification(applicationContext)
            return Result.failure()
        } catch (e: Exception) {
            val ignored = listOf(
                EOFException::class,
                SecurityException::class,
                SocketTimeoutException::class,
                TimeoutException::class
            )
            if (ignored.none { it.isInstance(e) }) showErrorNotification(applicationContext, e)

            if (PorterStateMachine.instance.update() == PorterStateMachine.State.RUNNING) {
                return Result.success()
            } else {
                updateNotification(
                    applicationContext,
                    WorkerState.AWAITING_RETRY
                )
                return Result.retry()
            }
        }
    }

    private fun showErrorNotification(context: Context, e: Exception) {
        NotificationChannels.create(context, NotificationChannels.ADB_START,
            context.getString(R.string.wadb_notification_title), NotificationManager.IMPORTANCE_LOW)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val nb = NotificationCompat.Builder(context, NotificationChannels.ADB_START)

        val msgNotif = "$e. ${context.getString(R.string.wadb_error_notify_dev)}"

        val intent = Intent(context, SupportActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = nb
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentTitle(context.getString(R.string.wadb_error_title))
            .setContentText(msgNotif)
            .setContentIntent(pendingIntent)
            .setSilent(true)
            .setStyle(NotificationCompat.BigTextStyle().bigText(msgNotif))
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun showPairingRequiredNotification(context: Context) {
        NotificationChannels.create(context, NotificationChannels.ADB_START,
            context.getString(R.string.wadb_notification_title), NotificationManager.IMPORTANCE_LOW)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val msg = context.getString(R.string.wadb_pair_required_notification)
        val pendingIntent = PendingIntent.getActivity(
            context, NOTIFICATION_ID, HomeActivity.pairingIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, NotificationChannels.ADB_START)
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentTitle(context.getString(R.string.adb_pair_required_title))
            .setContentText(msg)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setSilent(true)
            .setStyle(NotificationCompat.BigTextStyle().bigText(msg))
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        fun enqueue(context: Context) {
            val cb = Constraints.Builder()
            if (EnvironmentUtils.isWifiRequired())
                cb.setRequiredNetworkType(NetworkType.UNMETERED)
            val constraints = cb.build()

            val request = OneTimeWorkRequestBuilder<AdbStartWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "adb_start_worker",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
        const val NOTIFICATION_ID = 1448

        /**
         * How long a wait for the network to be authorized is given before the attempt is dropped
         * and left to the retry. Long enough for someone already looking at the prompt to answer
         * it, and short enough that a device where no one is looking is not held indefinitely. A
         * grace period, not a measured figure.
         */
        private const val AUTHORIZATION_TIMEOUT = 60_000L
    }
}