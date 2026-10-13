package eu.darken.porter.manager.compatibility

import android.app.Application
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import eu.darken.porter.common.CompatibilitySetup
import eu.darken.porter.manager.BuildConfig
import eu.darken.porter.manager.TestApplication
import eu.darken.porter.manager.compatibility.CompatibilityRepository.Companion.PACKAGE
import eu.darken.porter.manager.compatibility.CompatibilityRepository.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
@Config(application = TestApplication::class, sdk = [34])
class CompatibilityReplacementTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val packages = CompatibilityPackages(application)
    private val fork = "kerneldroid.nightzuku"
    private val dispatcher = StandardTestDispatcher()

    private val requests = mutableListOf<Pair<Int, String?>>()
    private val commands = mutableListOf<List<String>>()
    private var requestsBeforeRemoval: List<Pair<Int, String?>>? = null
    private var stops = 0
    private var users = intArrayOf(0)
    private var companionUsers = intArrayOf()
    private var protocolMismatch = false
    private var uninstallRemoves = true
    private var duringStop: () -> Unit = {}

    private fun createRepository(permissionOwner: (PackageManager) -> String? = OtherShizukuDetector::permissionOwner) =
        CompatibilityRepository(application,
            request = { operation, argument ->
                requests += operation to argument
                if (protocolMismatch) error("Restart Porter to use compatibility setup")
                Bundle().apply {
                    putInt("version", CompatibilitySetup.VERSION)
                    if (operation == CompatibilitySetup.INSPECT) {
                        putIntArray("users", users)
                        putIntArray("companionUsers", companionUsers)
                    }
                    if (operation == CompatibilitySetup.PREVIEW_IMPORT) putString("decisions", "[]")
                }
            },
            command = { arguments, _ ->
                commands += arguments.toList()
                if (arguments.take(2) == listOf("pm", "uninstall")) {
                    requestsBeforeRemoval = requests.toList()
                    if (uninstallRemoves) packages.remove(arguments.last())
                }
                "Success"
            },
            stopShizuku = { stops++; duringStop() },
            porterRunning = { true },
            permissionOwner = permissionOwner,
            scope = CoroutineScope(SupervisorJob() + dispatcher),
        )

    private val repository = createRepository()
    private val state get() = repository.state.value
    private val uninstalls get() = commands.filter { it.take(2) == listOf("pm", "uninstall") }

    private fun perform(action: CompatibilityRepository.() -> Unit, on: CompatibilityRepository = repository) {
        on.action()
        dispatcher.scheduler.advanceUntilIdle()
    }

    @Before fun signPorter() = packages.signPorter()

    private fun prepared() {
        perform({ prepareReplacement() })
        assertEquals(null, state.error)
        assertNotNull(state.preview)
    }

    private fun assertNotRemoved() {
        assertEquals(emptyList<List<String>>(), uninstalls)
        assertEquals(0, stops)
    }

    @Test fun foreignOwnerIsReplaceableConflict() {
        packages.install(fork, packages.otherSigner)
        perform({ refresh() })
        assertEquals(Status.CONFLICT, state.status)
        assertEquals(fork, state.conflictPackage)
        assertTrue(state.replaceable)
        assertFalse(state.isCompanion)
        assertEquals(listOf(CompatibilitySetup.INSPECT to fork), requests)
    }

    @Test fun replacementRemovesOnlyTheForeignOwner() {
        packages.install(fork, packages.otherSigner)
        prepared()
        perform({ replace() })
        assertEquals(listOf(listOf("pm", "uninstall", fork)), uninstalls)
        assertEquals(1, stops)
        val sent = checkNotNull(requestsBeforeRemoval)
        assertEquals(2, sent.count { it.first == CompatibilitySetup.PREVIEW_IMPORT })
        assertTrue(sent.isNotEmpty() && sent.all { it.second == fork })
        assertNotEquals("Shizuku was not removed", state.error)
    }

    @Test fun replacementOfTheOriginalPackageRemovesIt() {
        packages.install(PACKAGE, packages.otherSigner)
        prepared()
        assertEquals(PACKAGE, state.conflictPackage)
        perform({ replace() })
        assertEquals(listOf(listOf("pm", "uninstall", PACKAGE)), uninstalls)
        assertTrue(checkNotNull(requestsBeforeRemoval).all { it.second == PACKAGE })
    }

    @Test fun ownerThatSurvivesUninstallIsNotReplaced() {
        packages.install(fork, packages.otherSigner)
        uninstallRemoves = false
        prepared()
        perform({ replace() })
        assertEquals("Shizuku was not removed", state.error)
        assertEquals(listOf(listOf("pm", "uninstall", fork)), commands)
    }

    @Test fun ownerLookupFailureAbortsInspection() {
        packages.install(fork, packages.otherSigner)
        val failing = createRepository(permissionOwner = { throw SecurityException("denied") })
        perform({ refresh() }, failing)
        assertEquals("denied", failing.state.value.inspectionError)
        assertEquals(Status.MISSING, failing.state.value.status)
        assertEquals(null, failing.state.value.conflictPackage)
        perform({ prepareReplacement() }, failing)
        assertEquals("denied", failing.state.value.error)
        assertEquals(emptyList<Pair<Int, String?>>(), requests)
        assertEquals(emptyList<List<String>>(), commands)
    }

    @Test fun differentOwnerAfterReviewAbortsBeforeStopping() {
        packages.install(fork, packages.otherSigner)
        prepared()
        packages.remove(fork)
        packages.install("com.example.otherfork", packages.otherSigner)
        perform({ replace() })
        assertEquals("Installed apps changed; review replacement again", state.error)
        assertNotRemoved()
    }

    @Test fun differentCertificateAfterReviewAbortsBeforeStopping() {
        packages.install(fork, packages.otherSigner)
        prepared()
        packages.install(fork, packages.porterSigner)
        perform({ replace() })
        assertEquals("Installed apps changed; review replacement again", state.error)
        assertNotRemoved()
    }

    @Test fun differentCertificateWhileStoppingAbortsBeforeRemoval() {
        packages.install(fork, packages.otherSigner)
        prepared()
        duringStop = { packages.install(fork, packages.porterSigner) }
        perform({ replace() })
        assertEquals("Installed apps changed; review replacement again", state.error)
        assertEquals(emptyList<List<String>>(), uninstalls)
        assertEquals(1, stops)
        assertEquals(fork, requests.last { it.first == CompatibilitySetup.PREVIEW_IMPORT }.second)
    }

    private fun assertBlocks(block: () -> Unit) {
        packages.install(fork, packages.otherSigner)
        prepared()
        block()
        perform({ replace() })
        assertNotRemoved()
        perform({ refresh() })
        assertEquals(Status.CONFLICT, state.status)
        assertFalse(state.replaceable)
        requests.clear()
        perform({ prepareReplacement() })
        assertNotNull(state.error)
        assertFalse(requests.any { it.first == CompatibilitySetup.PREVIEW_IMPORT })
        assertNotRemoved()
    }

    @Test fun installForAnotherUserBlocksReplacement() = assertBlocks { users = intArrayOf(0, 10) }

    @Test fun hiddenOwnerBlocksReplacement() = assertBlocks {
        packages.remove(fork)
        packages.hiddenOwner(fork)
    }

    @Test fun systemOwnerBlocksReplacement() = assertBlocks { packages.install(fork, packages.otherSigner, system = true) }

    @Test fun appUnderTheCompanionPackageBlocksReplacement() =
        assertBlocks { packages.install(PACKAGE, packages.porterSigner, definesPermission = false) }

    @Test fun companionForAnotherUserBlocksReplacement() = assertBlocks { companionUsers = intArrayOf(10) }

    @Test fun protocolMismatchBlocksReplacement() = assertBlocks { protocolMismatch = true }

    @Test fun companionUninstallActsOnTheCompanion() {
        packages.install(PACKAGE, packages.porterSigner)
        perform({ uninstall() })
        assertEquals(listOf(listOf("pm", "uninstall", "--user", "0", PACKAGE)), commands)
        assertEquals(Status.MISSING, state.status)
    }

    @Test fun companionReinstallActsOnTheCompanion() {
        packages.install(PACKAGE, packages.porterSigner)
        perform({ reinstall() })
        assertNotEquals("Compatibility app cannot be reinstalled here", state.error)
        assertEquals(emptyList<List<String>>(), uninstalls)

        packages.remove(PACKAGE)
        packages.install(fork, packages.porterSigner)
        commands.clear()
        perform({ reinstall() })
        assertEquals("Compatibility app is no longer installed", state.error)
        assertEquals(emptyList<List<String>>(), commands)
    }

    @Test fun outdatedCompanionNeedsUpdate() {
        packages.install(PACKAGE, packages.porterSigner, version = BuildConfig.COMPAT_VERSION_CODE - 1L)
        perform({ refresh() })
        assertEquals(Status.UPDATE, state.status)
        assertEquals(null, state.conflictPackage)
        assertFalse(state.replaceable)
    }
}
