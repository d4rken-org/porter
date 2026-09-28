package eu.darken.porter.manager.updater

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import eu.darken.porter.manager.TestApplication
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [34])
class UnsupportedUpdateCheckerTest {

    @Test fun thePlayBuildNeverChecks() = runTest {
        val checker = UpdaterFlavor.checker(ApplicationProvider.getApplicationContext<Application>())
        assertSame(UnsupportedUpdateChecker, checker)
        assertFalse(checker.isSupported)
        assertFalse(checker.isEnabledByDefault())
        assertNull(checker.latest(includePrereleases = false))
        assertNull(checker.latest(includePrereleases = true))
    }
}
