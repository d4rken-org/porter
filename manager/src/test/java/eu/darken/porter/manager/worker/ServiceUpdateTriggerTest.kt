package eu.darken.porter.manager.worker

import android.app.Application
import android.content.Context
import android.os.Binder
import android.os.RemoteException
import androidx.test.core.app.ApplicationProvider
import eu.darken.porter.manager.PorterSettings
import eu.darken.porter.manager.TestApplication
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A connection offers the automatic update only for a server of another build, and only to users who opted in. */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [34])
class ServiceUpdateTriggerTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val server = Binder()
    private val scheduled = mutableListOf<Context>()
    private val originalSchedule = ServiceUpdateTrigger.schedule
    private val originalBuildCheck = ServiceUpdateTrigger.runsInstalledBuild

    @Before fun captureScheduling() {
        ServiceUpdateTrigger.schedule = { scheduled += it }
    }

    @After fun restore() {
        ServiceUpdateTrigger.schedule = originalSchedule
        ServiceUpdateTrigger.runsInstalledBuild = originalBuildCheck
    }

    private fun automaticUpdates(enabled: Boolean) {
        PorterSettings.preferences.edit().putBoolean(PorterSettings.Keys.KEY_AUTO_UPDATE_SERVICE, enabled).commit()
    }

    private fun serverRunsInstalledBuild(answer: () -> Boolean) {
        ServiceUpdateTrigger.runsInstalledBuild = { answer() }
    }

    @Test fun aServerOfAnotherBuildIsOfferedTheUpdate() {
        automaticUpdates(true)
        serverRunsInstalledBuild { false }
        ServiceUpdateTrigger.offer(application, server)
        assertEquals(1, scheduled.size)
    }

    @Test fun aServerOfTheInstalledBuildIsLeftAlone() {
        automaticUpdates(true)
        serverRunsInstalledBuild { true }
        ServiceUpdateTrigger.offer(application, server)
        assertEquals(0, scheduled.size)
    }

    @Test fun nothingIsOfferedWithoutTheOptIn() {
        automaticUpdates(false)
        serverRunsInstalledBuild { false }
        ServiceUpdateTrigger.offer(application, server)
        assertEquals(0, scheduled.size)
    }

    @Test fun aServerThatStoppedAnsweringIsLeftAlone() {
        automaticUpdates(true)
        serverRunsInstalledBuild { throw RemoteException("gone") }
        ServiceUpdateTrigger.offer(application, server)
        assertEquals(0, scheduled.size)
    }
}
