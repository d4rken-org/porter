package eu.darken.porter.manager.receiver

import android.app.Application
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import eu.darken.porter.manager.PorterSettings
import eu.darken.porter.manager.TestApplication
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Android 15 sends BOOT_COMPLETED to an app leaving the stopped state, and a running server takes
 * a force-stopped manager out of it. Starting on that delivery replaces the server that caused it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [34])
class BootStartTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()

    private fun boot(count: Int) {
        Settings.Global.putInt(application.contentResolver, Settings.Global.BOOT_COUNT, count)
    }

    @Before fun reset() {
        application.createDeviceProtectedStorageContext()
            .getSharedPreferences(PorterSettings.BOOT_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        PorterSettings.resetForTest()
        PorterSettings.initialize(application)
    }

    @Test fun onlyTheFirstDeliveryOfABootStarts() {
        boot(7)
        assertTrue(BootStart.claim(application))
        assertFalse(BootStart.claim(application))
    }

    @Test fun theNextBootStartsAgain() {
        boot(7)
        BootStart.claim(application)
        boot(8)
        assertTrue(BootStart.claim(application))
    }

    /**
     * A server started by hand, before start on boot was even turned on, or from a computer after
     * a boot the stopped manager never heard of.
     */
    @Test fun aBootThatAlreadyHadAServerNeedsNoStart() {
        boot(7)
        BootStart.serverSeen(application)
        assertFalse(BootStart.claim(application))
    }

    @Test fun aServerSeenInAnEarlierBootDoesNotCountForThisOne() {
        boot(7)
        BootStart.serverSeen(application)
        boot(8)
        assertTrue(BootStart.claim(application))
    }

    /** The claim has to outlive the process, which is exactly what a force-stop ends. */
    @Test fun theClaimSurvivesANewProcess() {
        boot(7)
        BootStart.claim(application)
        PorterSettings.resetForTest()
        PorterSettings.initialize(application)
        assertFalse(BootStart.claim(application))
    }

    @Test fun withoutABootCountEveryDeliveryStarts() {
        assertTrue(BootStart.claim(application))
        assertTrue(BootStart.claim(application))
    }
}
