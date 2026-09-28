package eu.darken.porter.manager.support

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import eu.darken.porter.manager.utils.Logger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DebugRecorderGateTest {
    @get:Rule val temporary = TemporaryFolder()
    private val application = ApplicationProvider.getApplicationContext<Application>()

    @After fun closeTheGate() {
        Logger.recording = false
    }

    /** A stop that finds no session on disk still ends debug-level logging. */
    @Test fun stopWithoutASessionMarkerClosesTheGate() {
        val store = object : DebugLogStore(temporary.newFolder()) {
            override fun activeId(): String? = null
        }
        Logger.recording = true

        runBlocking { DebugRecorder(application, storeOverride = store).stop() }

        assertFalse(Logger.recording)
    }
}
