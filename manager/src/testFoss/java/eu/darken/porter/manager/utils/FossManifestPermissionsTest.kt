package eu.darken.porter.manager.utils

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import eu.darken.porter.manager.TestApplication
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [34])
class FossManifestPermissionsTest {

    @Test fun `the FOSS build can ask for a battery exemption directly`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val requested = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty()
        assertTrue(Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS in requested)
    }
}
