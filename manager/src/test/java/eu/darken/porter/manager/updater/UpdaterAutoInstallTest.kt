package eu.darken.porter.manager.updater

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import eu.darken.porter.manager.R
import eu.darken.porter.manager.TestApplication
import eu.darken.porter.manager.updater.UpdateInstaller.Operation
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [34])
class UpdaterAutoInstallTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val fixture = UpdateInstallerFixture(application)
    private val staged get() = File(fixture.updates, "porter-0.7.0.apk")

    @Before fun reset() {
        fixture.updates.deleteRecursively()
    }

    @Test fun automaticInstallRunsPmWithTheStagedFile() = runTest {
        var stagedAtCommand: ByteArray? = null
        fixture.commandResult = { stagedAtCommand = staged.readBytes(); "Success" }
        val installer = fixture.installer(backgroundScope)
        installer.installAutomatically(fixture.asset())!!.join()
        assertEquals(listOf(listOf("pm", "install", "-r", "--user", "0", "-S", fixture.body.size.toString())), fixture.commands)
        assertEquals(listOf(staged.path to fixture.body.size.toLong()), fixture.commandFiles)
        assertTrue(fixture.body.contentEquals(stagedAtCommand))
        assertEquals(Operation.Idle, installer.operation.value)
    }

    @Test fun anArchiveOfAnotherPackageIsNotInstalled() = runTest {
        fixture.archive = { UpdateInstallerFixture.packageInfo("example.other", UpdateInstallerFixture.INSTALLED + 1) }
        val installer = fixture.installer(backgroundScope)
        installer.installAutomatically(fixture.asset())!!.join()
        assertEquals(Operation.Failed(application.getString(R.string.updater_not_porter)), installer.operation.value)
        installer.installManually(fixture.asset())!!.join()
        assertEquals(Operation.Failed(application.getString(R.string.updater_not_porter)), installer.operation.value)
        assertTrue(fixture.commands.isEmpty())
        assertNull(installer.handoff.value)
        assertTrue(fixture.updates.listFiles().isNullOrEmpty())
    }

    @Test fun anUnreadableArchiveIsNotInstalled() = runTest {
        fixture.archive = { null }
        val installer = fixture.installer(backgroundScope)
        installer.installAutomatically(fixture.asset())!!.join()
        assertEquals(Operation.Failed(application.getString(R.string.updater_not_porter)), installer.operation.value)
        assertTrue(fixture.commands.isEmpty())
    }

    @Test fun anArchiveThatIsNotNewerIsNotInstalled() = runTest {
        fixture.archive = { UpdateInstallerFixture.packageInfo(application.packageName, UpdateInstallerFixture.INSTALLED) }
        val installer = fixture.installer(backgroundScope)
        installer.installAutomatically(fixture.asset())!!.join()
        assertEquals(Operation.Failed(application.getString(R.string.updater_not_newer)), installer.operation.value)
        installer.installManually(fixture.asset())!!.join()
        assertEquals(Operation.Failed(application.getString(R.string.updater_not_newer)), installer.operation.value)
        assertTrue(fixture.commands.isEmpty())
        assertNull(installer.handoff.value)
    }

    @Test fun aServiceLostAfterTheDownloadStopsTheInstall() = runTest {
        fixture.archive = { fixture.available = false; UpdateInstallerFixture.packageInfo(application.packageName, 11) }
        val installer = fixture.installer(backgroundScope)
        installer.installAutomatically(fixture.asset())!!.join()
        assertEquals(Operation.Failed(application.getString(R.string.updater_automatic_unavailable)), installer.operation.value)
        assertTrue(fixture.commands.isEmpty())
    }

    @Test fun aFailedPmInstallReportsItsOutput() = runTest {
        fixture.commandResult = { throw IllegalStateException("Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]") }
        val installer = fixture.installer(backgroundScope)
        installer.installAutomatically(fixture.asset())!!.join()
        assertEquals(Operation.Failed(application.getString(R.string.updater_install_failed,
            "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]")), installer.operation.value)
        assertTrue(fixture.updates.listFiles().isNullOrEmpty())
    }

    @Test fun aSecondOperationWhileOneRunsIsIgnored() = runTest {
        val installer = fixture.installer(backgroundScope)
        val first = installer.installAutomatically(fixture.asset())
        assertEquals(Operation.Working(Operation.Kind.DOWNLOAD), installer.operation.value)
        assertNull(installer.save(fixture.asset(), Uri.parse("content://eu.darken.porter.test.documents/document/1")))
        assertNull(installer.installManually(fixture.asset()))
        assertNull(installer.installAutomatically(fixture.asset()))
        first!!.join()
        assertEquals(1, fixture.opened)
        assertEquals(1, fixture.commands.size)
        assertNull(installer.handoff.value)
    }

    @Test fun aManualInstallWaitsForAndroidsInstaller() = runTest {
        val installer = fixture.installer(backgroundScope)
        installer.installManually(fixture.asset())!!.join()
        assertEquals(Operation.Working(Operation.Kind.INSTALL), installer.operation.value)
        assertNull(installer.installAutomatically(fixture.asset()))
        assertEquals(staged, installer.takeHandoff())
        assertNull(installer.takeHandoff())
        installer.finishManualInstall()
        assertEquals(Operation.Idle, installer.operation.value)
        assertTrue(fixture.updates.listFiles().isNullOrEmpty())
        assertTrue(fixture.commands.isEmpty())
    }

    @Test fun anInstallerThatCannotOpenFailsTheManualInstall() = runTest {
        val installer = fixture.installer(backgroundScope)
        installer.installManually(fixture.asset())!!.join()
        installer.takeHandoff()
        installer.finishManualInstall("no installer")
        assertEquals(Operation.Failed("no installer"), installer.operation.value)
        installer.finishManualInstall()
        assertEquals(Operation.Failed("no installer"), installer.operation.value)
    }

    @Test fun aFailureReportedWhileIdleIsShownButNeverReplacesARunningOperation() = runTest {
        val installer = fixture.installer(backgroundScope)
        installer.reportFailure("denied")
        assertEquals(Operation.Failed("denied"), installer.operation.value)
        val job = installer.installAutomatically(fixture.asset())
        installer.reportFailure("late")
        assertEquals(Operation.Working(Operation.Kind.DOWNLOAD), installer.operation.value)
        job!!.join()
        assertEquals(Operation.Idle, installer.operation.value)
    }
}
