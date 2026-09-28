package eu.darken.porter.manager.support

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.TimeZone

/** Where a server stream's logcat starts for each attach. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerReplayTest {
    @get:Rule val temporary = TemporaryFolder()
    private val directory by lazy { temporary.newFolder() }
    private val zone = TimeZone.getTimeZone("Europe/Berlin")
    private val recordingStart = 1_790_607_903_123L
    private val session = ClockAnchors.Reading(recordingStart, 1_000L, 900L, zone, 7)

    /** A minute into the session on the same boot, with the wall clock moved by [movedMs]. */
    private fun attach(movedMs: Long = 0, boot: Int = 7) =
        ClockAnchors.Reading(recordingStart + 60_000L + movedMs, 61_000L, 60_900L, zone, boot)

    private fun choose(pid: Int = 42, attach: ClockAnchors.Reading = attach()) =
        chooseServerReplay(pid, recordingStart, session, attach, directory)

    private fun attached(pid: Int, boot: Int) =
        File(directory, "events.txt").appendText(attachedNote(pid, boot, 1, "1790607903.123") + " at 1790607903500\n")

    private fun written(name: String, at: Long) = File(directory, name).apply {
        writeText("line\n")
        assertTrue(setLastModified(at))
    }

    @Test fun aNewPidReplaysFromTheRecordingStart() {
        attached(pid = 41, boot = 7)
        written("server.log", 1_790_607_950_000L)

        assertEquals(ServerReplay("1790607903.123"), choose())
    }

    @Test fun aPidAttachedOnThisBootReplaysFromTheLastWrite() {
        attached(pid = 42, boot = 7)
        written("server.log", 1_790_607_950_000L)

        assertEquals(ServerReplay("1790607950.000"), choose())
    }

    @Test fun aNewerRotatedSegmentIsTheWatermark() {
        attached(pid = 42, boot = 7)
        written("server.log", 1_790_607_950_000L)
        written("server.log.1", 1_790_607_955_000L)

        assertEquals(ServerReplay("1790607955.000"), choose())
    }

    @Test fun aPidAttachedWithoutAnyLogYetReplaysFromTheRecordingStart() {
        attached(pid = 42, boot = 7)

        assertEquals(ServerReplay("1790607903.123"), choose())
    }

    @Test fun theSamePidOnAnotherBootIsANewInstance() {
        attached(pid = 42, boot = 6)
        written("server.log", 1_790_607_950_000L)

        assertEquals(ServerReplay("1790607903.123"), choose())
    }

    @Test fun anUnknownBootTreatsEveryPidAsNew() {
        attached(pid = 42, boot = -1)
        written("server.log", 1_790_607_950_000L)

        assertEquals(ServerReplay("1790607903.123"), choose(attach = attach(boot = -1)))
    }

    @Test fun onlyTheAttachedLineCountsAsHistory() {
        File(directory, "events.txt").writeText("Service binder arrived attach=1 pid=42 at 1790607903400\nServer stream ended pid=42 at 1790607903600\n")
        written("server.log", 1_790_607_950_000L)

        assertEquals(ServerReplay("1790607903.123"), choose())
    }

    @Test fun aWallClockJumpReplaysTheWholeBacklog() {
        attached(pid = 42, boot = 7)
        written("server.log", 1_790_607_950_000L)

        val replay = choose(attach = attach(movedMs = -1_001L))
        assertNull(replay.since)
        assertEquals("Replay cutoff unreliable: wall clock moved -1001ms", replay.note)
    }

    @Test fun aJumpWithinASecondKeepsTheTimeCutoff() {
        assertEquals(ServerReplay("1790607903.123"), choose(attach = attach(movedMs = 1_000L)))
    }

    @Test fun millisecondsArePaddedToThreeDigits() {
        assertEquals("1790607903.005", logcatTime(1_790_607_903_005L))
    }
}
