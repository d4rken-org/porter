package eu.darken.porter.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ConfigPersistenceTest {

    private class FailingWriter : ShizukuConfigManager() {
        var writes = 0

        override fun writeConfig(): Boolean {
            writes++
            return false
        }
    }

    @Test
    fun aFailedWriteRejectsThePauseAndKeepsTheStoredValue() {
        val manager = FailingWriter()
        assertFalse(manager.isAccessPaused)
        val writesBefore = manager.writes

        val failure = assertThrows(IllegalStateException::class.java) { manager.setAccessPaused(true) }
        assertEquals("App access pause could not be saved", failure.message)
        assertFalse(manager.isAccessPaused)
        assertEquals(writesBefore + 1, manager.writes)

        // Memory was realigned with disk, so the same value is attempted again instead of
        // hitting the same-value early return.
        assertThrows(IllegalStateException::class.java) { manager.setAccessPaused(true) }
        assertEquals(writesBefore + 2, manager.writes)
    }
}
