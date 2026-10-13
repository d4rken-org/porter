package eu.darken.porter.manager.compatibility

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import eu.darken.porter.manager.TestApplication
import eu.darken.porter.manager.compatibility.CompatibilityRepository.Companion.PACKAGE
import eu.darken.porter.manager.compatibility.CompatibilityRepository.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [34])
class CompatibilityResolutionTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val packages = CompatibilityPackages(application)
    private val fork = "kerneldroid.nightzuku"

    @Before fun signPorter() = packages.signPorter()

    private fun resolve() = application.packageManager.let { pm ->
        CompatibilityRepository.resolve(pm, OtherShizukuDetector.permissionOwner(pm), packages.porterCertificate)
    }

    @Test fun ownerIsThePackageDefiningThePermission() {
        assertNull(OtherShizukuDetector.permissionOwner(application.packageManager))
        packages.install(fork, packages.otherSigner)
        assertEquals(fork, OtherShizukuDetector.permissionOwner(application.packageManager))
    }

    @Test fun foreignOwnerIsAConflictWithItself() {
        packages.install(fork, packages.otherSigner)
        val resolution = resolve()
        assertEquals(Status.CONFLICT, resolution.status)
        assertEquals(fork, resolution.target)
        assertTrue(resolution.foreign)
        assertEquals(fork, resolution.targetInfo?.packageName)
        assertNull(resolution.companion)
    }

    @Test fun foreignOwnerSignedLikePorterIsNotTheCompanion() {
        packages.install(fork, packages.porterSigner)
        val resolution = resolve()
        assertEquals(Status.CONFLICT, resolution.status)
        assertEquals(fork, resolution.target)
        assertFalse(resolution.isCompanion)
    }

    @Test fun hiddenForeignOwnerIsAConflictWithoutDetails() {
        packages.hiddenOwner(fork)
        val resolution = resolve()
        assertEquals(Status.CONFLICT, resolution.status)
        assertEquals(fork, resolution.target)
        assertNull(resolution.targetInfo)
    }

    @Test fun originalPackageUnderAnotherSignerIsAConflictWithIt() {
        packages.install(PACKAGE, packages.otherSigner)
        val resolution = resolve()
        assertEquals(Status.CONFLICT, resolution.status)
        assertEquals(PACKAGE, resolution.target)
        assertFalse(resolution.foreign)
        assertFalse(resolution.isCompanion)
    }

    @Test fun currentCompanionIsInstalled() {
        packages.install(PACKAGE, packages.porterSigner)
        val resolution = resolve()
        assertEquals(Status.INSTALLED, resolution.status)
        assertEquals(PACKAGE, resolution.target)
        assertTrue(resolution.isCompanion)
    }

    @Test fun companionWithoutThePermissionIsInvalid() {
        packages.install(PACKAGE, packages.porterSigner, definesPermission = false)
        val resolution = resolve()
        assertEquals(Status.INVALID, resolution.status)
        assertTrue(resolution.isCompanion)
    }

    @Test fun nothingInstalledIsMissing() {
        val resolution = resolve()
        assertEquals(Status.MISSING, resolution.status)
        assertEquals(PACKAGE, resolution.target)
        assertFalse(resolution.foreign)
        assertNull(resolution.targetInfo)
    }

    @Test fun duplicatePermissionGroupNeedsRestart() {
        assertTrue(CompatibilityRepository.needsRestart("Failure [-126: Package moe.shizuku.privileged.api attempting to redeclare " +
            "permission group moe.shizuku.manager.permission-group.API already owned by kerneldroid.nightzuku]"))
    }

    @Test fun otherInstallFailuresDoNotNeedRestart() {
        assertFalse(CompatibilityRepository.needsRestart("Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]"))
        assertFalse(CompatibilityRepository.needsRestart(null))
    }
}
