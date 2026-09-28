package eu.darken.porter.manager.home

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import eu.darken.porter.manager.MainActivity
import eu.darken.porter.manager.PorterSettings
import eu.darken.porter.manager.TestApplication
import eu.darken.porter.manager.onboarding.OnboardingActivity
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
class HomeOnboardingGateTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()

    @Before fun resetSettings() {
        application.createDeviceProtectedStorageContext()
            .getSharedPreferences(PorterSettings.NAME, Context.MODE_PRIVATE).edit().clear().commit()
        PorterSettings.resetForTest()
        PorterSettings.initialize(application)
    }

    private fun launcherIntent() = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setClass(application, MainActivity::class.java)

    private fun assertRedirected(activity: MainActivity) {
        assertTrue(activity.isFinishing)
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(OnboardingActivity::class.java.name, started?.component?.className)
        val flags = started?.flags ?: 0
        assertTrue("reuses an onboarding already in the task", flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue("reuses an onboarding already on top", flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
    }

    @Test fun aLauncherStartOpensOnboardingWhileIncomplete() {
        val activity = Robolectric.buildActivity(MainActivity::class.java, launcherIntent()).create().get()
        assertRedirected(activity)
    }

    @Test fun anExplicitStartOpensOnboardingWhileIncomplete() {
        val activity = Robolectric.buildActivity(MainActivity::class.java, Intent(application, MainActivity::class.java)).create().get()
        assertRedirected(activity)
    }

    @Test fun aNewIntentOpensOnboardingWhileIncomplete() {
        assertTrue(PorterSettings.markOnboardingCompleted())
        val controller = Robolectric.buildActivity(MainActivity::class.java, launcherIntent()).create()
        assertFalse(controller.get().isFinishing)

        assertTrue(PorterSettings.preferences.edit().remove(PorterSettings.Keys.KEY_ONBOARDING_COMPLETED).commit())
        controller.newIntent(HomeActivity.pairingIntent(application))
        assertRedirected(controller.get())
    }

    @Test fun homeStaysOpenOnceCompleted() {
        assertTrue(PorterSettings.markOnboardingCompleted())
        val activity = Robolectric.buildActivity(MainActivity::class.java, launcherIntent()).create().get()
        assertFalse(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }
}
