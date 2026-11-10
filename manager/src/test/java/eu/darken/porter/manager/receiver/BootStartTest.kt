package eu.darken.porter.manager.receiver

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.os.Binder
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import eu.darken.porter.manager.PorterSettings
import eu.darken.porter.manager.TestApplication
import eu.darken.porter.manager.receiver.PorterReceiverStarter.WorkerState
import eu.darken.porter.manager.worker.AdbStartWorker
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Android 15 sends BOOT_COMPLETED to an app leaving the stopped state, and a running server takes
 * a force-stopped manager out of it. Starting on that delivery replaces the server that caused it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [34])
class BootStartTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val originalWorkStates = AdbStartWorker.workStates
    private val originalCancelWork = AdbStartWorker.cancelWork
    private var workCancels = 0

    private fun boot(count: Int) {
        Settings.Global.putInt(application.contentResolver, Settings.Global.BOOT_COUNT, count)
    }

    @Before fun reset() {
        application.createDeviceProtectedStorageContext()
            .getSharedPreferences(PorterSettings.BOOT_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        PorterSettings.resetForTest()
        PorterSettings.initialize(application)
        AdbStartWorker.cancelWork = { workCancels++ }
    }

    @After fun restore() {
        AdbStartWorker.workStates = originalWorkStates
        AdbStartWorker.cancelWork = originalCancelWork
    }

    private fun queued(vararg states: WorkInfo.State) {
        AdbStartWorker.workStates = { states.toList() }
    }

    private fun startNotification() = shadowOf(application.getSystemService(NotificationManager::class.java))
        .getNotification(PorterReceiverStarter.NOTIFICATION_ID)

    @Test fun onlyTheFirstDeliveryOfABootStarts() {
        boot(7)
        assertTrue(BootStart.claim(application))
        assertFalse(BootStart.claim(application))
    }

    @Test fun theNextBootStartsAgain() {
        boot(7)
        BootStart.claim(application)
        boot(8)
        assertTrue(BootStart.claim(application))
    }

    /**
     * A server started by hand, before start on boot was even turned on, or from a computer after
     * a boot the stopped manager never heard of.
     */
    @Test fun aBootThatAlreadyHadAServerNeedsNoStart() {
        boot(7)
        BootStart.serverSeen(application)
        assertFalse(BootStart.claim(application))
    }

    @Test fun aServerSeenInAnEarlierBootDoesNotCountForThisOne() {
        boot(7)
        BootStart.serverSeen(application)
        boot(8)
        assertTrue(BootStart.claim(application))
    }

    /** The claim has to outlive the process, which is exactly what a force-stop ends. */
    @Test fun theClaimSurvivesANewProcess() {
        boot(7)
        BootStart.claim(application)
        PorterSettings.resetForTest()
        PorterSettings.initialize(application)
        assertFalse(BootStart.claim(application))
    }

    @Test fun withoutABootCountEveryDeliveryStarts() {
        assertTrue(BootStart.claim(application))
        assertTrue(BootStart.claim(application))
    }

    /** As at a boot without Wi-Fi, followed by a root start from the app. */
    @Test fun aServerEndsTheStartWaitingForItsTurn() = runTest {
        queued(WorkInfo.State.ENQUEUED)
        PorterReceiverStarter.updateNotification(application, WorkerState.AWAITING_WIFI)

        BootStart.follow(application, flowOf(Binder()))

        assertEquals(1, workCancels)
        assertNull(startNotification())
    }

    /** The running attempt's own start delivers a server too. */
    @Test fun aRunningStartIsLeftToFinish() = runTest {
        queued(WorkInfo.State.RUNNING)
        PorterReceiverStarter.updateNotification(application, WorkerState.RUNNING)

        BootStart.follow(application, flowOf(Binder()))

        assertEquals(0, workCancels)
        assertNotNull(startNotification())
    }

    @Test fun aServerGoneEndsNothing() = runTest {
        queued(WorkInfo.State.ENQUEUED)

        BootStart.follow(application, flowOf(null))

        assertEquals(0, workCancels)
    }

    @Test fun aFailedLookupLeavesTheNextServerHandled() = runTest {
        var lookups = 0
        AdbStartWorker.workStates = {
            if (++lookups == 1) throw IllegalStateException("WorkManager is not initialized")
            listOf(WorkInfo.State.ENQUEUED)
        }

        BootStart.follow(application, flowOf(Binder(), Binder()))

        assertEquals(2, lookups)
        assertEquals(1, workCancels)
    }
}
