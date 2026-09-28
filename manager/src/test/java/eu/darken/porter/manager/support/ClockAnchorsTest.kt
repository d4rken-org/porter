package eu.darken.porter.manager.support

import android.app.Application
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClockAnchorsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val clocks = FixedClocks(elapsedMs = 5_000_123L, uptimeMs = 4_000_456L)
    private val anchors = ClockAnchors(clocks)

    @Test fun aPositiveOffsetZone() {
        assertEquals(
            "Clock attach 1 wall=2026-09-28T17:05:03.123+0200 epochMs=1790607903123 elapsedMs=5000123 uptimeMs=4000456 bootCount=7",
            anchors.line("attach 1"),
        )
    }

    @Test fun aNegativeOffsetZone() {
        clocks.zone = TimeZone.getTimeZone("America/New_York")
        assertEquals(
            "Clock stop wall=2026-09-28T11:05:03.123-0400 epochMs=1790607903123 elapsedMs=5000123 uptimeMs=4000456 bootCount=7",
            anchors.line("stop"),
        )
    }

    @Test fun anUnreadableBootCountReadsAsMinusOne() {
        val device = ClockAnchors.DeviceClocks(application)
        assertEquals(-1, device.bootCount())

        clocks.bootCount = device.bootCount()
        assertEquals(
            "Clock lost wall=2026-09-28T17:05:03.123+0200 epochMs=1790607903123 elapsedMs=5000123 uptimeMs=4000456 bootCount=-1",
            anchors.line("lost"),
        )
    }

    @Test fun theDeviceBootCountIsTheOneTheBootGateReads() {
        Settings.Global.putInt(application.contentResolver, Settings.Global.BOOT_COUNT, 12)
        assertEquals(12, ClockAnchors.DeviceClocks(application).bootCount())
    }

    @Test fun theOffsetIsWallMinusElapsed() {
        val reading = anchors.read()
        assertEquals(1_790_607_903_123L - 5_000_123L, reading.wallMinusElapsedMs)
        assertEquals(7, reading.bootCount)
    }

    @Test fun theMetadataCarriesTheAnchorRightAfterItsTime() = runBlocking {
        clocks.zone = TimeZone.getTimeZone("America/New_York")
        val file = File(temporary.newFolder(), "server-attach-1-pid1.txt")

        ServerDiagnostics.captureMetadata(application, null, file, anchors)

        assertEquals(
            listOf("Time: 1790607903123", "Elapsed: 5000123", "Zone: America/New_York -0400", "Boot count: 7"),
            file.readLines().take(4),
        )
    }
}
