package eu.darken.porter.manager.worker

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Binder
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.ProgressUpdater
import androidx.work.WorkInfo
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.utils.taskexecutor.SerialExecutor
import androidx.work.impl.utils.taskexecutor.TaskExecutor
import androidx.work.workDataOf
import eu.darken.porter.manager.NotificationChannels
import eu.darken.porter.manager.PorterApplication
import eu.darken.porter.manager.PorterSettings
import eu.darken.porter.manager.R
import eu.darken.porter.manager.ServerBinder
import eu.darken.porter.manager.receiver.PorterReceiverStarter
import eu.darken.porter.manager.receiver.PorterReceiverStarter.WorkerState
import eu.darken.porter.manager.utils.PorterStateMachine
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.TimeoutException
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemProperties
import org.robolectric.util.ReflectionHelpers

/**
 * A start attempt that a server started some other way overtakes ends without a trace. The class
 * runs below API 28 so the application's static setup skips its ART-only hidden-API bypass, and
 * with an adbd TCP port so an attempt goes straight to its start.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class AdbStartWorkerTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val nm = application.getSystemService(NotificationManager::class.java)
    private val starts = mutableListOf<Int>()
    private val originalStart = AdbStartWorker.startServer

    @Before fun setUp() {
        // EnvironmentUtils takes its context from the production application, which no unit test creates.
        ReflectionHelpers.setStaticField(PorterApplication::class.java, "appContext", application)
        ShadowSystemProperties.override("service.adb.tcp.port", "5555")
        forgetServer()
        PorterSettings.resetForTest()
        PorterSettings.initialize(application)
        PorterStateMachine.instance.set(PorterStateMachine.State.STOPPED)
        Settings.Global.putInt(application.contentResolver, Settings.Global.ADB_ENABLED, 0)
        AdbStartWorker.startServer = { _, port, _ -> starts += port }
    }

    @After fun restore() {
        AdbStartWorker.startServer = originalStart
        forgetServer()
    }

    private fun forgetServer() {
        ServerBinder.binder.value?.let { ServerBinder.drop(it) }
    }

    private fun serverArrives() = ServerBinder.deliver(Binder())

    private val direct = Executor { it.run() }

    private fun worker(force: Boolean = false) = AdbStartWorker(application, WorkerParameters(
        UUID.randomUUID(),
        workDataOf(AdbStartWorker.KEY_FORCE to force),
        emptyList(),
        WorkerParameters.RuntimeExtras(),
        0,
        0,
        direct,
        EmptyCoroutineContext,
        object : TaskExecutor {
            override fun getMainThreadExecutor(): Executor = direct
            override fun getSerialTaskExecutor(): SerialExecutor = object : SerialExecutor {
                override fun execute(command: Runnable) = command.run()
                override fun hasPendingTasks() = false
            }
        },
        object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? = null
        },
        ProgressUpdater { _, _, _ -> error("these runs report no progress") },
        ForegroundUpdater { _, _, _ -> error("these runs never wait behind the keyguard") },
    ))

    private fun startNotification(): Notification? = shadowOf(nm).getNotification(PorterReceiverStarter.NOTIFICATION_ID)

    private fun adbEnabled() = Settings.Global.getInt(application.contentResolver, Settings.Global.ADB_ENABLED, -1)

    private suspend fun AdbStartWorker.cancelled() {
        try {
            doWork()
            fail("the cancellation was swallowed")
        } catch (e: CancellationException) {
        }
    }

    @Test fun aWaitingAttemptAloneIsSuperseded() {
        assertTrue(AdbStartWorker.supersedes(listOf(WorkInfo.State.ENQUEUED)))
    }

    /** The replaced request can still be finishing next to its successor. */
    @Test fun anyRunningAttemptIsLeftToFinish() {
        assertFalse(AdbStartWorker.supersedes(listOf(WorkInfo.State.RUNNING)))
        assertFalse(AdbStartWorker.supersedes(listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING)))
        assertFalse(AdbStartWorker.supersedes(listOf(WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED)))
    }

    @Test fun nothingWaitingIsNothingToSupersede() {
        assertFalse(AdbStartWorker.supersedes(emptyList()))
        for (state in listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.CANCELLED, WorkInfo.State.FAILED)) {
            assertFalse(state.name, AdbStartWorker.supersedes(listOf(state)))
        }
    }

    /** Every notification of the attempt is built on its channel, so a missing channel means none was posted. */
    @Test fun anAttemptAServerOvertookTouchesNothing() = runTest {
        serverArrives()

        assertEquals(ListenableWorker.Result.success(), worker().doWork())

        assertEquals(0, adbEnabled())
        assertEquals(emptyList<Int>(), starts)
        assertNull(nm.getNotificationChannel(NotificationChannels.ADB_START))
    }

    @Test fun anAttemptAServerOvertookTakesItsWaitingNotificationDown() = runTest {
        PorterReceiverStarter.updateNotification(application, WorkerState.AWAITING_WIFI)
        serverArrives()

        worker().doWork()

        assertNull(startNotification())
    }

    /** A changed TCP port needs the running server replaced. */
    @Test fun aForcedAttemptReplacesARunningServer() = runTest {
        serverArrives()

        assertEquals(ListenableWorker.Result.success(), worker(force = true).doWork())

        assertEquals(1, adbEnabled())
        assertEquals(listOf(5555), starts)
    }

    @Test fun aCancelledAttemptAServerOvertookLeavesNoNotification() = runTest {
        AdbStartWorker.startServer = { _, _, _ ->
            serverArrives()
            throw CancellationException("cancelled")
        }

        worker().cancelled()

        assertNull(startNotification())
    }

    /** Below Android 12 a cancellation cannot tell its reason, so it waits for the retry. */
    @Test fun aCancelledAttemptWithoutAServerWaitsForTheRetry() = runTest {
        AdbStartWorker.startServer = { _, _, _ -> throw CancellationException("cancelled") }

        worker().cancelled()

        assertEquals(
            application.getString(R.string.wadb_notification_retry),
            startNotification()?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
    }

    @Test fun aHandedOffAttemptStillShowsItself() = runTest {
        val attempt = worker()
        AdbStartWorker.startServer = { _, _, _ ->
            serverArrives()
            attempt.handedOff = true
            throw CancellationException("handed off")
        }

        attempt.cancelled()

        val notification = startNotification()
        assertNotNull(notification)
        assertNull(notification!!.extras.getCharSequence(Notification.EXTRA_TEXT))
    }

    /** As when the attempt's discovery runs out while a root start brings the server up. */
    @Test fun aTimedOutAttemptAServerOvertookLeavesNoNotification() = runTest {
        AdbStartWorker.startServer = { _, _, _ ->
            serverArrives()
            throw TimeoutException("Timed out during mDNS port discovery")
        }

        assertEquals(ListenableWorker.Result.success(), worker().doWork())

        assertNull(startNotification())
    }

    @Test fun aFailedAttemptAServerOvertookKeepsItsError() = runTest {
        AdbStartWorker.startServer = { _, _, _ ->
            serverArrives()
            throw IllegalStateException("adbd went away")
        }

        assertEquals(ListenableWorker.Result.success(), worker().doWork())

        assertNull(startNotification())
        assertNotNull(shadowOf(nm).getNotification(AdbStartWorker.NOTIFICATION_ID))
    }
}
