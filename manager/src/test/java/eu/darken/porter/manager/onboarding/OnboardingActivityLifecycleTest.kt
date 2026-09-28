package eu.darken.porter.manager.onboarding

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import eu.darken.porter.manager.MainActivity
import eu.darken.porter.manager.PorterSettings
import eu.darken.porter.manager.TestApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [34])
class OnboardingActivityLifecycleTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()

    @Before fun resetSettings() {
        application.createDeviceProtectedStorageContext()
            .getSharedPreferences(PorterSettings.NAME, Context.MODE_PRIVATE).edit().clear().commit()
        PorterSettings.resetForTest()
        PorterSettings.initialize(application)
    }

    /** An onboarding instance left in the back stack comes back after another one completed. */
    @Test fun aRestartAfterCompletionOpensHome() {
        val controller = Robolectric.buildActivity(OnboardingActivity::class.java).create().start().resume().visible()
        val activity = controller.get()
        assertFalse(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)

        controller.pause().stop()
        assertTrue(PorterSettings.markOnboardingCompleted())
        // On API 34, restart() also runs onStart.
        controller.restart().resume()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(MainActivity::class.java.name, shadowOf(activity).nextStartedActivity?.component?.className)
        assertTrue("onboarding stays open after completion", activity.isFinishing)
    }

    @Test fun homeReplacesTheTaskOnceCompleted() {
        val controller = Robolectric.buildActivity(OnboardingActivity::class.java).create().start().resume().visible()
        controller.pause().stop()
        assertTrue(PorterSettings.markOnboardingCompleted())
        controller.restart().resume()
        shadowOf(Looper.getMainLooper()).idle()

        val started = shadowOf(controller.get()).nextStartedActivity
        assertEquals(MainActivity::class.java.name, started?.component?.className)
        val flags = started?.flags ?: 0
        assertTrue("home starts in a task of its own", flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue("home clears the onboarding below it", flags and Intent.FLAG_ACTIVITY_CLEAR_TASK != 0)
    }

    /** Completion lands while onboarding is stopped: the restart and the buffered event both want Home. */
    @Test fun completingWhileStoppedOpensHomeOnce() {
        val controller = Robolectric.buildActivity(OnboardingActivity::class.java).create().start().resume().visible()
        val activity = controller.get()
        val model = ViewModelProvider(activity)[OnboardingViewModel::class.java]

        controller.pause().stop()
        model.finish()
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && !PorterSettings.onboardingCompleted) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        // Let the resumed coroutine run on main and buffer OpenHome.
        repeat(10) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertTrue(PorterSettings.onboardingCompleted)
        assertNull("OpenHome delivered while stopped", shadowOf(activity).nextStartedActivity)

        controller.restart().resume()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(MainActivity::class.java.name, shadowOf(activity).nextStartedActivity?.component?.className)
        assertNull("Home started twice", shadowOf(activity).nextStartedActivity)
    }
}
