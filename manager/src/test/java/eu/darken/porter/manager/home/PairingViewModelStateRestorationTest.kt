package eu.darken.porter.manager.home

import android.app.Application
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SavedStateViewModelFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.test.core.app.ApplicationProvider
import eu.darken.porter.manager.TestApplication
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [34])
class PairingViewModelStateRestorationTest {

    /**
     * Stands in for the fragment host across process death: it owns both the saved state registry,
     * which survives as a [Bundle], and the view model store, which does not. It has to be a
     * [ViewModelStoreOwner] as well, because a handle-backed view model registers a recreator that
     * refuses to restore into anything else.
     */
    private class Host(restored: Bundle?) : SavedStateRegistryOwner, ViewModelStoreOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val controller = SavedStateRegistryController.create(this)
        override val viewModelStore = ViewModelStore()
        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry

        init {
            controller.performAttach()
            controller.performRestore(restored)
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }

        fun viewModel(application: Application): PairingViewModel =
            ViewModelProvider(viewModelStore, SavedStateViewModelFactory(application, this))[PairingViewModel::class.java]

        fun saveAndDie(): Bundle = Bundle().also {
            controller.performSave(it)
            viewModelStore.clear()
        }
    }

    @Test fun typedPortAndCodeSurviveProcessDeath() {
        val application = ApplicationProvider.getApplicationContext<Application>()

        val before = Host(null)
        before.viewModel(application).apply {
            port.value = "37419"
            code.value = "314159"
        }
        val saved = before.saveAndDie()

        val after = Host(saved).viewModel(application)
        assertEquals("37419", after.port.value)
        assertEquals("314159", after.code.value)
    }

    /** The restored view model has no endpoint, so the first resolve must not read as a move to a new one. */
    @Test fun theFirstDiscoveryAfterRestorationKeepsWhatWasTyped() {
        val application = ApplicationProvider.getApplicationContext<Application>()

        val before = Host(null)
        before.viewModel(application).apply {
            port.value = "37419"
            code.value = "314159"
        }
        val saved = before.saveAndDie()

        val after = Host(saved).viewModel(application)
        after.onDiscovered("192.168.1.5" to 41234)
        assertEquals("314159", after.code.value)
        assertEquals("37419", after.port.value)
    }

    /** Once an endpoint has been resolved, the next resolve is the live one and its port takes over. */
    @Test fun aLaterDiscoveryReplacesThePortOfTheEndpointItSupersedes() {
        val application = ApplicationProvider.getApplicationContext<Application>()

        val model = Host(null).viewModel(application)
        model.onDiscovered("192.168.1.5" to 41234)
        assertEquals("41234", model.port.value)

        model.onDiscovered("192.168.1.5" to 45678)
        assertEquals("45678", model.port.value)
    }
}
