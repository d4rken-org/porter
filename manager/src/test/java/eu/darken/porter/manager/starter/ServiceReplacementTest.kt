package eu.darken.porter.manager.starter

import android.app.Application
import android.content.Context
import android.os.Binder
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import eu.darken.porter.manager.PorterSettings
import eu.darken.porter.manager.model.PorterServiceVersion
import eu.darken.porter.manager.support.ServerDiagnostics
import eu.darken.porter.manager.utils.PorterStateMachine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** What the replacement decides for itself, now that it can be built over a test context. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServiceReplacementTest {
    @get:Rule val temporary = TemporaryFolder()
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val installed = PorterServiceVersion.installed
    private val old = Binder()
    private val next = Binder()
    private var current: IBinder = old

    private val updateRecord get() = application.createDeviceProtectedStorageContext()
        .getSharedPreferences("service-update", Context.MODE_PRIVATE)

    @Before fun allowAutomaticUpdates() {
        PorterSettings.getPreferences().edit()
            .putBoolean(PorterSettings.Keys.KEY_AUTO_UPDATE_SERVICE, true).commit()
        PorterStateMachine.instance.set(PorterStateMachine.State.STOPPED)
    }

    @After fun dropTheInstance() {
        ServiceReplacement.resetForTest()
        PorterStateMachine.instance.set(PorterStateMachine.State.STOPPED)
    }

    /** The starter the replace path insists on, which Robolectric leaves out of the ApplicationInfo. */
    private fun installStarter() {
        val libraries = temporary.newFolder()
        File(libraries, "libshizuku.so").apply { writeText("starter"); setExecutable(true) }
        application.applicationInfo.sourceDir = temporary.newFile("base.apk").absolutePath
        application.applicationInfo.nativeLibraryDir = libraries.absolutePath
    }

    private fun replacement(launch: suspend () -> Unit) = ServiceReplacement(
        application,
        currentBinder = { current },
        readInfo = {
            if (it === old) ServerDiagnostics.Info(100, installed.copy(buildId = "old:debug"))
            else ServerDiagnostics.Info(200, installed)
        },
        readUid = { 2000 },
        launch = { _, _, _, _ -> launch() },
    )

    @Test fun theUpdateRecordIsKeptInDeviceProtectedStorage() {
        application.getSharedPreferences("service-update", Context.MODE_PRIVATE).edit()
            .putString("target", installed.buildId).putString("phase", "FAILED").commit()

        assertFalse(
            "the record was read from credential-protected storage, which is unreadable until the " +
                "user unlocks the device",
            ServiceReplacement(application).state.value.failed,
        )

        updateRecord.edit().putString("target", installed.buildId).putString("phase", "FAILED").commit()

        assertTrue(ServiceReplacement(application).state.value.failed)
    }

    @Test fun anUnavailableStarterIsRecordedAsTheFailure() = runTest {
        val outcome = ServiceReplacement(application).updateInBackground()

        assertEquals(ServiceUpdateController.Outcome.FAILED, outcome)
        assertTrue(
            "the recorded failure was: ${updateRecord.getString("detail", null)}",
            updateRecord.getString("detail", null).orEmpty()
                .contains("The installed Porter starter is unavailable"),
        )
    }

    @Test fun theServiceIsMarkedStartingBeforeTheStarterRuns() = runTest {
        installStarter()
        var whenLaunched: PorterStateMachine.State? = null

        val outcome = replacement {
            whenLaunched = PorterStateMachine.instance.get()
            current = next
            PorterStateMachine.instance.set(PorterStateMachine.State.RUNNING)
        }.updateInBackground()

        assertEquals(ServiceUpdateController.Outcome.UPDATED, outcome)
        assertEquals(
            "the starter ran before the service was marked starting, so the watchdog sees the " +
                "handoff as a crash",
            PorterStateMachine.State.STARTING,
            whenLaunched,
        )
    }

    @Test fun finishingRereadsTheServiceState() = runTest {
        installStarter()

        replacement {
            current = next
            PorterStateMachine.instance.set(PorterStateMachine.State.RUNNING)
        }.updateInBackground()

        // No binder answers here, so a state reread ends at STOPPED rather than at what the run left.
        assertEquals(PorterStateMachine.State.STOPPED, PorterStateMachine.instance.get())
    }
}
