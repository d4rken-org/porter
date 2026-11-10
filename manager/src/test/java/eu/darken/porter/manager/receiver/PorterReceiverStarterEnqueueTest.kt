package eu.darken.porter.manager.receiver

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.os.Binder
import androidx.test.core.app.ApplicationProvider
import androidx.work.OneTimeWorkRequest
import eu.darken.porter.manager.PorterApplication
import eu.darken.porter.manager.PorterSettings
import eu.darken.porter.manager.R
import eu.darken.porter.manager.ServerBinder
import eu.darken.porter.manager.utils.PorterStateMachine
import eu.darken.porter.manager.worker.AdbStartWorker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemProperties
import org.robolectric.util.ReflectionHelpers

/**
 * A server can arrive while an ADB start's request is being stored, before the binder hook can
 * find it. The class runs below API 28 so the application's static setup skips its ART-only
 * hidden-API bypass, and with an adbd TCP port, which is what an ADB start needs there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class PorterReceiverStarterEnqueueTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val nm = application.getSystemService(NotificationManager::class.java)
    private val originalEnqueue = AdbStartWorker.enqueueUnique
    private val originalCancelWork = AdbStartWorker.cancelWork
    private val requests = mutableListOf<OneTimeWorkRequest>()
    private var onEnqueued: (() -> Unit)? = null
    private var notifiedBeforeEnqueue = false
    private var workCancels = 0

    @Before fun setUp() {
        // EnvironmentUtils takes its context from the production application, which no unit test creates.
        ReflectionHelpers.setStaticField(PorterApplication::class.java, "appContext", application)
        ShadowSystemProperties.override("service.adb.tcp.port", "5555")
        shadowOf(application).grantPermissions(WRITE_SECURE_SETTINGS)
        forgetServer()
        PorterSettings.resetForTest()
        PorterSettings.initialize(application)
        PorterSettings.lastLaunchMode = PorterSettings.LaunchMethod.ADB
        PorterStateMachine.instance.set(PorterStateMachine.State.STOPPED)
        AdbStartWorker.enqueueUnique = { _, request, callback ->
            notifiedBeforeEnqueue = startNotification() != null
            requests += request
            onEnqueued = callback
        }
        AdbStartWorker.cancelWork = { workCancels++ }
    }

    @After fun restore() {
        AdbStartWorker.enqueueUnique = originalEnqueue
        AdbStartWorker.cancelWork = originalCancelWork
        forgetServer()
    }

    private fun forgetServer() {
        ServerBinder.binder.value?.let { ServerBinder.drop(it) }
    }

    private fun startNotification(): Notification? = shadowOf(nm).getNotification(PorterReceiverStarter.NOTIFICATION_ID)

    private fun forced() = requests.single().workSpec.input.getBoolean(AdbStartWorker.KEY_FORCE, false)

    /** A server delivered later finds both the stored request and the notification to end. */
    @Test fun theNotificationIsUpBeforeTheRequestIsStored() {
        PorterReceiverStarter.start(application)
        assertTrue(notifiedBeforeEnqueue)
    }

    @Test fun aServerThatArrivesWhileTheRequestIsStoredEndsTheStart() {
        PorterReceiverStarter.start(application)
        ServerBinder.deliver(Binder())
        onEnqueued!!.invoke()

        assertEquals(1, workCancels)
        assertNull(startNotification())
    }

    @Test fun withoutAServerTheStartKeepsWaiting() {
        PorterReceiverStarter.start(application)
        onEnqueued!!.invoke()

        assertEquals(0, workCancels)
        assertFalse(forced())
        assertEquals(
            application.getString(R.string.wadb_notification_wifi_required),
            startNotification()?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
    }

    /** A changed TCP port restarts the server it finds running. */
    @Test fun aForcedStartIsNotCheckedAgainstTheRunningServer() {
        ServerBinder.deliver(Binder())

        PorterReceiverStarter.start(application, forceStart = true)

        assertNull(onEnqueued)
        assertTrue(forced())
        assertEquals(0, workCancels)
    }
}
