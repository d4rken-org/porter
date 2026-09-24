package eu.darken.porter.manager.receiver

import android.app.Application
import android.app.NotificationManager
import android.os.Binder
import androidx.test.core.app.ApplicationProvider
import eu.darken.porter.manager.PorterSettings
import eu.darken.porter.manager.ServerBinder
import eu.darken.porter.manager.TestApplication
import eu.darken.porter.manager.utils.PorterStateMachine
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [34])
class PorterReceiverStarterTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()

    @Before fun reset() {
        forgetBinder()
        PorterSettings.resetForTest()
        PorterSettings.initialize(application)
        PorterSettings.lastLaunchMode = PorterSettings.LaunchMethod.ADB
        PorterStateMachine.instance.set(PorterStateMachine.State.STOPPED)
    }

    @After fun forgetBinder() {
        ServerBinder.binder.value?.let { ServerBinder.drop(it) }
    }

    /** Without WRITE_SECURE_SETTINGS, an ADB start that goes ahead posts its permission error. */
    private fun startWentAhead() = shadowOf(application.getSystemService(NotificationManager::class.java))
        .getNotification(PorterReceiverStarter.NOTIFICATION_ID) != null

    @Test fun aDeliveredServerIsNotReplacedBeforeTheStateMachineHearsOfIt() {
        ServerBinder.deliver(Binder())
        PorterReceiverStarter.start(application)
        assertFalse(startWentAhead())
    }

    @Test fun withoutAServerTheStartGoesAhead() {
        PorterReceiverStarter.start(application)
        assertTrue(startWentAhead())
    }
}
