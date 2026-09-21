package eu.darken.porter.privileged

import android.system.Os
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mockStatic
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowProcess
import java.io.File
import java.io.FileDescriptor
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ConnectionHistoryTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun latestConnectionSurvivesRestartAndProfilesStaySeparate() {
        ShadowProcess.setUid(2000)
        val file = File(temporary.root, "connections.json")
        val history = ConnectionHistory(file)
        val owner = installedApp("same.app", 10123)
        val profile = installedApp("same.app", 1010123)
        assertEquals(0, history.get(owner))
        history.connected(owner, 1000)
        history.connected(profile, 2000)
        history.connected(owner, 3000)
        val reloaded = ConnectionHistory(file)
        assertEquals(3000, reloaded.get(owner))
        assertEquals(2000, reloaded.get(profile))
    }

    @Test
    fun reinstallOrUidReuseDoesNotInheritConnectionTime() {
        ShadowProcess.setUid(2000)
        val history = ConnectionHistory(File(temporary.root, "connections.json"))
        val app = installedApp("example", 10123)
        history.connected(app, 1000)
        app.firstInstallTime++
        assertEquals(0, history.get(app))
        app.firstInstallTime--
        app.applicationInfo!!.uid++
        assertEquals(0, history.get(app))
    }

    @Test
    fun pruningOneSuccessfullyEnumeratedUserPreservesOtherProfiles() {
        ShadowProcess.setUid(2000)
        val file = File(temporary.root, "connections.json")
        val history = ConnectionHistory(file)
        val owner = installedApp("owner", 10123)
        val profile = installedApp("profile", 1010123)
        history.connected(owner, 1000)
        history.connected(profile, 2000)
        history.pruneUser(0, listOf(), 3000)
        val reloaded = ConnectionHistory(file)
        assertEquals(0, reloaded.get(owner))
        assertEquals(2000, reloaded.get(profile))
    }

    @Test
    fun oldSnapshotCannotRemoveNewConnectionAndLateWritesCannotMoveTimeBackwards() {
        ShadowProcess.setUid(2000)
        val history = ConnectionHistory(File(temporary.root, "connections.json"))
        val app = installedApp("example", 10123)
        history.connected(app, 3000)
        history.connected(app, 2000)
        history.pruneUser(0, listOf(), 2500)
        assertEquals(3000, history.get(app))
    }

    @Test
    fun brokenHistoryDoesNotAffectNewConnections() {
        ShadowProcess.setUid(2000)
        val file = temporary.newFile("connections.json")
        Files.writeString(file.toPath(), "broken json")
        val history = ConnectionHistory(file)
        val app = installedApp("example", 10123)
        assertEquals(0, history.get(app))
        history.connected(app, 1234)
        assertEquals(1234, ConnectionHistory(file).get(app))
    }

    @Test
    fun theFirstBareArrayFormatStillLoads() {
        ShadowProcess.setUid(2000)
        val file = temporary.newFile("connections.json")
        Files.writeString(file.toPath(), """[{"key":"0:example","uid":10123,"installed":500,"connected":1234}]""")
        val app = installedApp("example", 10123)
        app.firstInstallTime = 500
        val history = ConnectionHistory(file)
        assertEquals(1234, history.get(app))
        history.connected(app, 2000)
        assertTrue(Files.readString(file.toPath()).startsWith("{\"version\":1,"))
        assertEquals(2000, ConnectionHistory(file).get(app))
    }

    @Test
    fun anUnsupportedVersionStartsEmptyWithoutBreakingNewConnections() {
        ShadowProcess.setUid(2000)
        val record = """[{"key":"0:example","uid":10123,"installed":500,"connected":1234}]"""
        for (version in listOf("2", "1.5", "4294967297", "\"1\"", "null")) {
            val file = File(temporary.root, "connections-$version.json")
            Files.writeString(file.toPath(), """{"version":$version,"connections":$record}""")
            val app = installedApp("example", 10123)
            app.firstInstallTime = 500
            val history = ConnectionHistory(file)
            assertEquals(version, 0, history.get(app))
            history.connected(app, 2000)
            assertEquals(version, 2000, ConnectionHistory(file).get(app))
        }
    }

    @Test
    fun rootWritesRemainOwnedByShell() {
        ShadowProcess.setUid(0)
        val file = File(temporary.root, "connections.json")
        val history = ConnectionHistory(file)
        mockStatic(Os::class.java).use { os ->
            history.connected(installedApp("example", 10123), 1234)
            os.verify { Os.fchown(any(FileDescriptor::class.java), eq(2000), eq(2000)) }
            // 0600 in the Java; Kotlin has no octal literal.
            os.verify { Os.fchmod(any(FileDescriptor::class.java), eq(0x180)) }
        }
        assertEquals(1234, ConnectionHistory(file).get(installedApp("example", 10123)))
    }
}
