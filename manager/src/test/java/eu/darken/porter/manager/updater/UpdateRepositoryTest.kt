package eu.darken.porter.manager.updater

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.porter.manager.PorterSettings
import eu.darken.porter.manager.TestApplication
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [34])
class UpdateRepositoryTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val prefs = application.getSharedPreferences("updater-test", Context.MODE_PRIVATE)
    private val checker = FakeChecker()
    private var now = 1_700_000_000_000L

    private class FakeChecker : UpdateChecker {
        var supported = true
        var enabledByDefault = true
        val requests = mutableListOf<Boolean>()
        var answer: suspend (Boolean) -> Release? = { NEWER }

        override val isSupported: Boolean get() = supported
        override fun isEnabledByDefault(): Boolean = enabledByDefault
        override suspend fun latest(includePrereleases: Boolean): Release? {
            requests += includePrereleases
            return answer(includePrereleases)
        }
    }

    @Before fun reset() {
        prefs.edit().clear().commit()
        PorterSettings.updateCheck = null
        PorterSettings.updateChannel = null
    }

    private fun TestScope.repository(version: String = INSTALLED) =
        UpdateRepository(checker, prefs, version, { now }, backgroundScope)

    private fun hours(count: Int) = count * 60 * 60 * 1000L

    @Test fun defaultOffChecksNeverFetch() = runTest {
        checker.enabledByDefault = false
        val repository = repository()
        repository.refresh().join()
        assertTrue(checker.requests.isEmpty())
        assertFalse(repository.state.value.enabled)
        assertNull(repository.state.value.update)
    }

    @Test fun aStoredOptOutOverridesTheDefault() = runTest {
        PorterSettings.updateCheck = false
        val repository = repository()
        repository.refresh().join()
        assertTrue(checker.requests.isEmpty())
        assertNull(repository.state.value.update)
    }

    @Test fun anUnsupportedCheckerNeverFetches() = runTest {
        checker.supported = false
        PorterSettings.updateCheck = true
        val repository = repository()
        repository.refresh().join()
        assertTrue(checker.requests.isEmpty())
        assertFalse(repository.state.value.supported)
        assertFalse(repository.state.value.enabled)
    }

    @Test fun aNewerReleaseIsOffered() = runTest {
        val repository = repository()
        repository.refresh().join()
        assertEquals(AvailableUpdate(INSTALLED, NEWER), repository.state.value.update)
    }

    @Test fun aReleaseThatIsNotNewerIsNotOffered() = runTest {
        checker.answer = { release("v$INSTALLED") }
        val repository = repository()
        repository.refresh().join()
        assertNull(repository.state.value.update)

        now += hours(24)
        checker.answer = { release("v0.6.9-rc9") }
        repository.refresh().join()
        assertNull(repository.state.value.update)
    }

    @Test fun aSecondRefreshWithinADayUsesTheCache() = runTest {
        val repository = repository()
        repository.refresh().join()
        now += hours(23)
        repository.refresh().join()
        assertEquals(1, checker.requests.size)
        assertEquals(NEWER, repository.state.value.update?.release)
    }

    @Test fun noReleaseAtAllIsCachedToo() = runTest {
        checker.answer = { null }
        val repository = repository()
        repository.refresh().join()
        now += hours(1)
        repository.refresh().join()
        assertEquals(1, checker.requests.size)
        assertNull(repository.state.value.update)
    }

    @Test fun aRefreshAfterADayFetchesAgain() = runTest {
        val repository = repository()
        repository.refresh().join()
        now += hours(24)
        checker.answer = { NEWEST }
        repository.refresh().join()
        assertEquals(2, checker.requests.size)
        assertEquals(NEWEST, repository.state.value.update?.release)
    }

    @Test fun aFailureKeepsTheCacheAndWaitsAnHourToRetry() = runTest {
        val repository = repository()
        repository.refresh().join()
        now += hours(25)
        checker.answer = { throw IOException("offline") }
        repository.refresh().join()
        assertEquals(2, checker.requests.size)
        assertEquals(NEWER, repository.state.value.update?.release)

        now += hours(1) - 1
        repository.refresh().join()
        assertEquals(2, checker.requests.size)

        now += 1
        checker.answer = { NEWEST }
        repository.refresh().join()
        assertEquals(3, checker.requests.size)
        assertEquals(NEWEST, repository.state.value.update?.release)
    }

    @Test fun aCheckFromTheFutureCountsAsDue() = runTest {
        val repository = repository()
        repository.refresh().join()
        now -= hours(1)
        repository.refresh().join()
        assertEquals(2, checker.requests.size)
    }

    @Test fun aFailureFromTheFutureCountsAsDue() = runTest {
        checker.answer = { throw IOException("offline") }
        val repository = repository()
        repository.refresh().join()
        now -= 1
        repository.refresh().join()
        assertEquals(2, checker.requests.size)
    }

    @Test fun concurrentRefreshesFetchOnce() = runTest {
        val gate = CompletableDeferred<Release?>()
        checker.answer = { gate.await() }
        val repository = repository()
        val first = repository.refresh()
        val second = repository.refresh()
        runCurrent()
        gate.complete(NEWER)
        joinAll(first, second)
        assertEquals(1, checker.requests.size)
        assertEquals(NEWER, repository.state.value.update?.release)
    }

    @Test fun disablingDuringAFetchKeepsTheUpdateHidden() = runTest {
        val gate = CompletableDeferred<Release?>()
        checker.answer = { gate.await() }
        val repository = repository()
        val check = repository.refresh()
        runCurrent()
        assertNull(repository.setEnabled(false))
        gate.complete(NEWER)
        check.join()
        assertNull(repository.state.value.update)
        assertFalse(repository.state.value.enabled)
    }

    @Test fun dismissingDuringAFetchKeepsTheUpdateHidden() = runTest {
        val repository = repository()
        repository.refresh().join()
        now += hours(25)
        val gate = CompletableDeferred<Release?>()
        checker.answer = { gate.await() }
        val check = repository.refresh()
        runCurrent()
        repository.dismiss()
        gate.complete(NEWER)
        check.join()
        assertEquals(2, checker.requests.size)
        assertNull(repository.state.value.update)
    }

    @Test fun aDismissedReleaseStaysHiddenUntilANewerOne() = runTest {
        val repository = repository()
        repository.refresh().join()
        repository.dismiss()
        assertNull(repository.state.value.update)

        now += hours(24)
        repository.refresh().join()
        assertNull(repository.state.value.update)

        now += hours(24)
        checker.answer = { NEWEST }
        repository.refresh().join()
        assertEquals(NEWEST, repository.state.value.update?.release)
    }

    @Test fun enablingChecksRightAway() = runTest {
        checker.enabledByDefault = false
        val repository = repository()
        val check = repository.setEnabled(true)
        assertNotNull(check)
        check?.join()
        assertEquals(1, checker.requests.size)
        assertEquals(true, PorterSettings.updateCheck)
        assertEquals(NEWER, repository.state.value.update?.release)
    }

    @Test fun enablingWithoutCheckingNowStoresAndLeavesTheFetchToTheNextRefresh() = runTest {
        checker.enabledByDefault = false
        val repository = repository()
        assertNull(repository.setEnabled(true, checkNow = false))
        runCurrent()
        assertTrue(checker.requests.isEmpty())
        assertEquals(true, PorterSettings.updateCheck)
        assertTrue(repository.state.value.enabled)

        repository.refresh().join()
        assertEquals(1, checker.requests.size)
        assertEquals(NEWER, repository.state.value.update?.release)
    }

    @Test fun disablingWithoutCheckingNowKeepsTheNextRefreshOffline() = runTest {
        val repository = repository()
        assertNull(repository.setEnabled(false, checkNow = false))
        assertEquals(false, PorterSettings.updateCheck)
        assertFalse(repository.state.value.enabled)
        repository.refresh().join()
        assertTrue(checker.requests.isEmpty())
    }

    @Test fun theChannelFollowsTheInstalledVersionUntilChosen() = runTest {
        assertEquals(UpdateChannel.BETA, repository("0.7.0-beta2").state.value.channel)
        assertEquals(UpdateChannel.PRODUCTION, repository("0.7.0-rc0").state.value.channel)
        PorterSettings.updateChannel = UpdateChannel.PRODUCTION
        assertEquals(UpdateChannel.PRODUCTION, repository("0.7.0-beta2").state.value.channel)
    }

    @Test fun onlyTheBetaChannelAsksForPrereleases() = runTest {
        repository("0.7.0-rc0").refresh().join()
        repository("0.7.0-beta2").refresh().join()
        assertEquals(listOf(false, true), checker.requests)
    }

    @Test fun switchingChannelRefetchesDespiteAFreshCache() = runTest {
        val repository = repository()
        repository.refresh().join()
        checker.answer = { NEWEST }
        val check = repository.setChannel(UpdateChannel.BETA)
        assertNotNull(check)
        check?.join()
        assertEquals(listOf(false, true), checker.requests)
        assertEquals(UpdateChannel.BETA, PorterSettings.updateChannel)
        assertEquals(UpdateChannel.BETA, repository.state.value.channel)
        assertEquals(NEWEST, repository.state.value.update?.release)
    }

    private companion object {
        const val INSTALLED = "0.7.0-rc0"

        fun release(tag: String) = Release(
            tag = tag,
            name = "Porter $tag",
            changelogUrl = "https://github.com/d4rken-org/porter/releases/tag/$tag",
            apk = Asset(
                fileName = "porter-$tag-release.apk",
                url = "https://github.com/d4rken-org/porter/releases/download/$tag/porter-$tag-release.apk",
                size = 4702924L,
            ),
        )

        val NEWER = release("v0.7.0-rc1")
        val NEWEST = release("v0.7.1-beta0")
    }
}
