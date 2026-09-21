package eu.darken.porter.manager.receiver

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import eu.darken.porter.manager.NotificationChannels
import eu.darken.porter.manager.PorterSettings
import eu.darken.porter.manager.TestApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The receivers are exported without a permission, so an unauthenticated broadcast must not become a way to spam the user. */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [34])
class AuthenticatedReceiverTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val notifications = application.getSystemService(NotificationManager::class.java)

    private var authenticated = 0
    private val authenticatedOn = CountDownLatch(1)
    private var authenticatedThread: Thread? = null
    private val receiver = object : AuthenticatedReceiver() {
        override fun onAuthenticated(context: Context, intent: Intent) {
            authenticated++
            authenticatedThread = Thread.currentThread()
            authenticatedOn.countDown()
        }
    }

    private fun receive(token: String?) = receiver.onReceive(application, Intent("test").apply { putExtra("auth", token) })

    private fun authNotifications(): Int =
        notifications.activeNotifications.count { it.notification.channelId == NotificationChannels.AUTH }

    @Before fun reset() {
        AuthenticatedReceiver.resetForTest()
        notifications.cancelAll()
    }

    @Test fun aMissingTokenIsOnlyLogged() {
        receive(null)
        receive("")
        assertEquals(0, authNotifications())
        assertEquals(0, authenticated)
    }

    @Test fun aWrongTokenNotifiesOncePerInterval() {
        val wrong = "not-" + PorterSettings.authToken
        receive(wrong)
        assertEquals(1, authNotifications())
        notifications.cancelAll()
        receive(wrong)
        assertEquals(0, authNotifications())
        ShadowSystemClock.advanceBy(Duration.ofMinutes(5))
        receive(wrong)
        assertEquals(1, authNotifications())
        assertEquals(0, authenticated)
    }

    /** Delivered by the framework shadow so the receiver holds a pending result, as it does on a device. */
    @Test fun theRightTokenRunsTheWorkOffTheMainThreadAndFinishesTheBroadcast() {
        application.registerReceiver(receiver, IntentFilter("test"))
        application.sendBroadcast(Intent("test").putExtra("auth", PorterSettings.authToken))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(authenticatedOn.await(5, TimeUnit.SECONDS))
        assertEquals(1, authenticated)
        assertTrue(authenticatedThread != Looper.getMainLooper().thread)
        assertTrue(shadowOf(receiver).wentAsync())
        shadowOf(shadowOf(receiver).originalPendingResult).future.get(5, TimeUnit.SECONDS)
        assertEquals(0, authNotifications())
    }
}
