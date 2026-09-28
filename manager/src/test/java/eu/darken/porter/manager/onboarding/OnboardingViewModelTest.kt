package eu.darken.porter.manager.onboarding

import android.app.Application
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import eu.darken.porter.manager.PorterSettings
import eu.darken.porter.manager.TestApplication
import eu.darken.porter.manager.onboarding.OnboardingPage.PRIVACY
import eu.darken.porter.manager.onboarding.OnboardingPage.SHIZUKU
import eu.darken.porter.manager.onboarding.OnboardingPage.WELCOME
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [34])
class OnboardingViewModelTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        application.createDeviceProtectedStorageContext().getSharedPreferences(PorterSettings.NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        PorterSettings.resetForTest()
        PorterSettings.initialize(application)
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun model(
        handle: SavedStateHandle = SavedStateHandle(),
        detect: (Context) -> Boolean = { false },
        markCompleted: () -> Boolean = { PorterSettings.markOnboardingCompleted() },
    ) = OnboardingViewModel(application, handle, detect, markCompleted, dispatcher)

    private fun advance() = dispatcher.scheduler.advanceUntilIdle()

    private fun OnboardingViewModel.drain(): List<OnboardingEvent> =
        generateSequence { events.tryReceive().getOrNull() }.toList()

    private fun restoredFrom(source: SavedStateHandle) = SavedStateHandle(source.keys().associateWith { source.get<Any>(it) })

    @Test fun withoutShizukuThePagesAreWelcomeAndPrivacy() {
        val model = model(detect = { false })
        assertEquals(listOf(WELCOME, PRIVACY), model.pages)
        assertEquals(WELCOME, model.page.value)
    }

    @Test fun withShizukuItsPageSitsBetweenWelcomeAndPrivacy() {
        assertEquals(listOf(WELCOME, SHIZUKU, PRIVACY), model(detect = { true }).pages)
    }

    @Test fun nextAndBackWalkThePagesInOrder() {
        val model = model(detect = { true })
        model.next()
        assertEquals(SHIZUKU, model.page.value)
        model.next()
        assertEquals(PRIVACY, model.page.value)
        model.next()
        assertEquals(PRIVACY, model.page.value)
        model.back()
        assertEquals(SHIZUKU, model.page.value)
        model.back()
        assertEquals(WELCOME, model.page.value)
        assertEquals(emptyList<OnboardingEvent>(), model.drain())
    }

    @Test fun backOnTheFirstPageClosesWithoutCompleting() {
        val model = model()
        model.back()
        advance()
        assertEquals(listOf(OnboardingEvent.Close), model.drain())
        assertEquals(WELCOME, model.page.value)
        assertFalse(PorterSettings.onboardingCompleted)
    }

    @Test fun finishingTwiceStoresCompletionAndOpensHomeOnce() {
        var writes = 0
        val model = model(markCompleted = { writes++; PorterSettings.markOnboardingCompleted() })
        model.next()
        model.finish()
        assertTrue(model.finishing.value)
        model.finish()
        advance()
        assertEquals(1, writes)
        assertTrue(PorterSettings.onboardingCompleted)
        assertEquals(listOf(OnboardingEvent.OpenHome), model.drain())
    }

    @Test fun aFailedWriteStaysOnThePageAndARetryOpensHome() {
        val results = ArrayDeque(listOf(false, true))
        val model = model(markCompleted = { results.removeFirst() })
        model.next()
        model.finish()
        advance()
        assertEquals(PRIVACY, model.page.value)
        assertTrue(model.saveFailed.value)
        assertFalse(model.finishing.value)
        assertEquals(emptyList<OnboardingEvent>(), model.drain())

        model.finish()
        advance()
        assertFalse(model.saveFailed.value)
        assertEquals(listOf(OnboardingEvent.OpenHome), model.drain())
    }

    @Test fun aThrowingWriteCountsAsFailed() {
        val model = model(markCompleted = { throw IllegalStateException("disk full") })
        model.next()
        model.finish()
        advance()
        assertTrue(model.saveFailed.value)
        assertFalse(model.finishing.value)
        assertEquals(emptyList<OnboardingEvent>(), model.drain())
    }

    @Test fun theStoredPagesAndPageWinOverANewDetection() {
        var detections = 0
        val handle = SavedStateHandle()
        val first = model(handle, detect = { detections++; true })
        first.next()
        first.next()
        assertEquals(PRIVACY, first.page.value)

        val second = model(restoredFrom(handle), detect = { detections++; false })
        assertEquals(listOf(WELCOME, SHIZUKU, PRIVACY), second.pages)
        assertEquals(PRIVACY, second.page.value)
        assertEquals(1, detections)
    }

    @Test fun linksOpenThePrivacyPolicyAndTheCompatibilityGuide() {
        val model = model()
        model.openPrivacyPolicy()
        model.openCompatibilityGuide()
        assertEquals(
            listOf(
                OnboardingEvent.OpenUrl("https://porter.darken.eu/privacy"),
                OnboardingEvent.OpenUrl("https://porter.darken.eu/compatibility"),
            ),
            model.drain(),
        )
    }
}
