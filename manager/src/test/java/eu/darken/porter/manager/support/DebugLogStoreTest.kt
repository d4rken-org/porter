package eu.darken.porter.manager.support

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class DebugLogStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun resumeAndProtectActiveSession() {
        val root = temporary.newFolder()
        val store = DebugLogStore(root)
        val id = store.create()
        assertEquals(id, DebugLogStore(root).activeId())
        assertThrows(IllegalStateException::class.java) { store.delete(id) }
        assertThrows(IllegalStateException::class.java) { store.export(id, temporary.newFolder()) }
        store.finish()
        assertNull(store.activeId())
    }
    @Test fun logAppendPreservesEarlierProcessAndBoundsTotalBytes() {
        val file = temporary.newFile()
        file.writeText("first")
        DebugLogStore.appendBounded("second".byteInputStream(), file, 8)
        assertEquals("firstsec", file.readText())
        DebugLogStore.appendBounded("third".byteInputStream(), file, 8)
        assertEquals("firstsec", file.readText())
    }
    @Test fun rotationKeepsNewestBytesAndBoundsThePair() {
        val file = temporary.newFile()
        DebugLogStore.appendRotating("abcdefghij".byteInputStream(), file, 8)
        assertEquals("ij", file.readText())
        assertEquals("efgh", File(file.parentFile, "${file.name}.1").readText())
    }
    @Test fun rotationCountsBytesLeftByAnEarlierProcess() {
        val file = temporary.newFile()
        file.writeText("xyz")
        DebugLogStore.appendRotating("ab".byteInputStream(), file, 8)
        assertEquals("b", file.readText())
        assertEquals("xyza", File(file.parentFile, "${file.name}.1").readText())
    }
    @Test fun rotationDropsTheOldestSegmentAndFillsTheLimit() {
        val file = temporary.newFile()
        DebugLogStore.appendRotating("abcdefghijkl".byteInputStream(), file, 8)
        val rotated = File(file.parentFile, "${file.name}.1")
        assertEquals("ijkl", file.readText())
        assertEquals("efgh", rotated.readText())
        assertEquals(8, file.length() + rotated.length())
    }
    @Test fun rotationTrimsALogLeftOversizedByABuildThatDidNotRotate() {
        val file = temporary.newFile()
        file.writeText("abcdefgh")
        DebugLogStore.appendRotating("ij".byteInputStream(), file, 8)
        val rotated = File(file.parentFile, "${file.name}.1")
        assertEquals("ij", file.readText())
        assertEquals("efgh", rotated.readText())
        assertFalse(File(file.parentFile, "${file.name}.tail").exists())
    }
    @Test fun rotatedSegmentsSurviveAResumeAndReachTheArchive() {
        val store = DebugLogStore(temporary.newFolder())
        val id = store.create()
        val file = File(store.directory(id), "manager.log")
        DebugLogStore.appendRotating("abcdef".byteInputStream(), file, 8)
        DebugLogStore.appendRotating("ghij".byteInputStream(), file, 8)
        store.finish()
        ZipFile(store.export(id, temporary.newFolder())).use { zip ->
            assertEquals(2, zip.size())
            // The resume rotated "efgh" into place, so the segment the first call left is gone.
            assertEquals("efgh", zip.getInputStream(zip.getEntry("manager.log.1")).bufferedReader().readText())
            assertEquals("ij", zip.getInputStream(zip.getEntry("manager.log")).bufferedReader().readText())
        }
    }
    @Test fun retentionKeepsFiveCompletedAndCurrentSession() {
        val store = DebugLogStore(temporary.newFolder())
        repeat(8) { store.create(100L + it); store.finish() }
        store.prune()
        assertEquals(listOf(107L,106L,105L,104L,103L), store.sessions().map { it.started })
        val active = store.create(108)
        assertEquals(6, store.sessions().size)
        assertTrue(store.sessions().first().active)
        assertEquals(active, store.activeId())
    }
    @Test fun archiveContainsCompletedLogsAndRejectsTraversal() {
        val store = DebugLogStore(temporary.newFolder())
        val id = store.create()
        File(store.directory(id), "manager.log").writeText("manager output")
        File(store.directory(id), "server-stop.txt").writeText("service unavailable")
        store.finish()
        ZipFile(store.export(id, temporary.newFolder())).use { zip ->
            assertEquals(2, zip.size())
            assertEquals("manager output", zip.getInputStream(zip.getEntry("manager.log")).bufferedReader().readText())
        }
        assertThrows(IllegalArgumentException::class.java) { store.directory("../outside") }
        store.delete(id)
        assertTrue(store.sessions().isEmpty())
    }
    @Test fun interruptedMarkerDoesNotEscapePrivateStorage() {
        val root = temporary.newFolder()
        File(root, "active").writeText("../../settings")
        assertNull(DebugLogStore(root).activeId())
    }
}
