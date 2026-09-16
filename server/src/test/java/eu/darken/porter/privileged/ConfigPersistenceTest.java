package eu.darken.porter.privileged;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class ConfigPersistenceTest {

    private static class FailingWriter extends ShizukuConfigManager {
        int writes;

        @Override
        boolean writeConfig() {
            writes++;
            return false;
        }
    }

    @Test
    public void aFailedWriteRejectsThePauseAndKeepsTheStoredValue() {
        FailingWriter manager = new FailingWriter();
        assertFalse(manager.isAccessPaused());
        int writesBefore = manager.writes;

        IllegalStateException failure =
                assertThrows(IllegalStateException.class, () -> manager.setAccessPaused(true));
        assertEquals("App access pause could not be saved", failure.getMessage());
        assertFalse(manager.isAccessPaused());
        assertEquals(writesBefore + 1, manager.writes);

        // Memory was realigned with disk, so the same value is attempted again instead of
        // hitting the same-value early return.
        assertThrows(IllegalStateException.class, () -> manager.setAccessPaused(true));
        assertEquals(writesBefore + 2, manager.writes);
    }
}
