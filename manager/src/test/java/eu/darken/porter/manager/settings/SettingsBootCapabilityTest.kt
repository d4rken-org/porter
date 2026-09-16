package eu.darken.porter.manager.settings

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import eu.darken.porter.manager.PorterApplication
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * Start-on-boot capability resolution. The class runs below API 28 so the application's static
 * setup skips its ART-only hidden-API bypass; any level under 30 reaches the root probe.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class SettingsBootCapabilityTest {
    private val dispatcher = StandardTestDispatcher()
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private var probes = 0

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        // EnvironmentUtils takes its context from the production application, which no unit test
        // creates; only the probe branch reaches it.
        if (Build.VERSION.SDK_INT < 30) {
            ReflectionHelpers.setStaticField(PorterApplication::class.java, "appContext", application)
        }
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun model(probe: suspend () -> Boolean) =
        SettingsViewModel(application, SavedStateHandle()) { probes++; probe() }

    private fun advance() = dispatcher.scheduler.advanceUntilIdle()

    @Test @Config(sdk = [34]) fun apiThirtyAndAboveResolvesWithoutProbing() {
        val model = model { true }
        assertEquals(true, model.canBoot.value)
        advance()
        assertEquals(0, probes)
    }

    @Test fun televisionResolvesWithoutProbing() {
        shadowOf(application.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        val model = model { true }
        assertEquals(true, model.canBoot.value)
        advance()
        assertEquals(0, probes)
    }

    @Test fun aDelayedProbeLeavesTheValueUnresolvedUntilItAnswers() {
        val answer = CompletableDeferred<Boolean>()
        val model = model { answer.await() }
        assertNull(model.canBoot.value)
        advance()
        assertEquals(1, probes)
        assertNull(model.canBoot.value)
        answer.complete(true)
        advance()
        assertEquals(true, model.canBoot.value)
    }

    @Test fun aDeniedProbeResolvesToNoBoot() {
        val model = model { false }
        advance()
        assertEquals(false, model.canBoot.value)
    }

    @Test fun aFailedProbeResolvesToNoBoot() {
        val model = model { throw IllegalStateException("shell unavailable") }
        advance()
        assertEquals(false, model.canBoot.value)
    }

    @Test fun aCancelledProbeLeavesTheValueUnresolved() {
        val answer = CompletableDeferred<Boolean>()
        val model = model { answer.await() }
        val store = ViewModelStore().apply { put("settings", model) }
        advance()
        assertEquals(1, probes)
        store.clear()
        advance()
        assertNull(model.canBoot.value)
    }
}
