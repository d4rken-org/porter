package eu.darken.porter.manager.onboarding

import android.content.Context
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import eu.darken.porter.manager.ComposeTest
import eu.darken.porter.manager.R
import eu.darken.porter.manager.onboarding.OnboardingPage.PRIVACY
import eu.darken.porter.manager.onboarding.OnboardingPage.SHIZUKU
import eu.darken.porter.manager.onboarding.OnboardingPage.WELCOME
import eu.darken.porter.manager.ui.PorterTheme
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.annotation.Config

/** Tall viewport so every text is on screen without scrolling, except where a test narrows it. */
@Config(qualifiers = "w411dp-h891dp")
class OnboardingScreenContentTest : ComposeTest() {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun string(id: Int) = context.getString(id)

    private val clicks = mutableListOf<String>()
    private val actions = OnboardingActions(
        onNext = { clicks += "next" },
        onBack = { clicks += "back" },
        onFinish = { clicks += "finish" },
        onPrivacyPolicy = { clicks += "privacyPolicy" },
        onCompatibilityGuide = { clicks += "compatibilityGuide" },
    )

    private fun state(page: OnboardingPage, isBeta: Boolean = false, finishing: Boolean = false, saveFailed: Boolean = false) =
        OnboardingUiState(listOf(WELCOME, SHIZUKU, PRIVACY), page, isBeta, finishing, saveFailed)

    private var backDispatcher: OnBackPressedDispatcher? = null

    private fun render(initial: OnboardingUiState, fontScale: Float? = null, keyboard: Boolean = false): MutableState<OnboardingUiState> {
        val ui = mutableStateOf(initial)
        composeTestRule.setContent {
            if (keyboard) {
                val inputMode = LocalInputModeManager.current
                SideEffect { inputMode.requestInputMode(InputMode.Keyboard) }
            }
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale ?: density.fontScale)) {
                PorterTheme(dark = false) { OnboardingScreenContent(ui.value, actions) }
            }
        }
        return ui
    }

    private fun node(id: Int) = composeTestRule.onNodeWithText(string(id))

    @Test fun theBetaDisclaimerShowsOnlyOnBetaBuilds() {
        val ui = render(state(WELCOME, isBeta = false))
        node(R.string.onboarding_welcome_title).assertIsDisplayed()
        node(R.string.onboarding_welcome_beta).assertDoesNotExist()

        composeTestRule.runOnIdle { ui.value = state(WELCOME, isBeta = true) }
        node(R.string.onboarding_welcome_beta).assertExists()
    }

    @Test fun theWelcomePageOnlyContinues() {
        render(state(WELCOME))
        node(R.string.onboarding_shizuku_guide).assertDoesNotExist()
        node(R.string.onboarding_privacy_policy).assertDoesNotExist()
        node(R.string.onboarding_continue).assertIsDisplayed().performClick()
        assertEquals(listOf("next"), clicks)
    }

    @Test fun theShizukuPageShowsItsTitleAndBothButtons() {
        render(state(SHIZUKU))
        node(R.string.onboarding_shizuku_title).assertIsDisplayed()
        node(R.string.onboarding_shizuku_guide).performScrollTo().assertIsDisplayed().performClick()
        node(R.string.onboarding_continue).assertIsDisplayed().performClick()
        assertEquals(listOf("compatibilityGuide", "next"), clicks)
    }

    @Test fun thePrivacyPageOpensThePolicyAndGetsStarted() {
        render(state(PRIVACY))
        node(R.string.onboarding_privacy_title).assertIsDisplayed()
        node(R.string.onboarding_privacy_policy).performScrollTo().assertIsDisplayed().performClick()
        node(R.string.onboarding_get_started).assertIsDisplayed().performClick()
        assertEquals(listOf("privacyPolicy", "finish"), clicks)
    }

    @Test fun systemBackIsHandedToTheActions() {
        render(state(SHIZUKU))
        composeTestRule.runOnIdle { checkNotNull(backDispatcher).onBackPressed() }
        assertEquals(listOf("back"), clicks)
    }

    @Test fun getStartedIsDisabledWhileFinishing() {
        val ui = render(state(PRIVACY, finishing = true))
        node(R.string.onboarding_get_started).assertIsNotEnabled()

        composeTestRule.runOnIdle { ui.value = state(PRIVACY) }
        node(R.string.onboarding_get_started).assertIsEnabled()
    }

    @Test fun aFailedSaveShowsTheError() {
        val ui = render(state(PRIVACY))
        node(R.string.onboarding_save_failed).assertDoesNotExist()

        composeTestRule.runOnIdle { ui.value = state(PRIVACY, saveFailed = true) }
        node(R.string.onboarding_save_failed).assertIsDisplayed()
        node(R.string.onboarding_get_started).assertIsEnabled()
    }

    @Config(qualifiers = "w640dp-h360dp")
    @Test fun aShortLargeFontViewportKeepsTheButtonsAndScrollsTheText() {
        val ui = render(state(SHIZUKU), fontScale = 1.5f)
        node(R.string.onboarding_continue).assertIsDisplayed()
        node(R.string.onboarding_shizuku_replace).performScrollTo().assertIsDisplayed()
        node(R.string.onboarding_shizuku_guide).performScrollTo().assertIsDisplayed()
        node(R.string.onboarding_continue).assertIsDisplayed()

        composeTestRule.runOnIdle { ui.value = state(WELCOME, isBeta = true) }
        node(R.string.onboarding_continue).assertIsDisplayed()
        node(R.string.onboarding_welcome_beta).performScrollTo().assertIsDisplayed()
    }

    @Test fun thePrimaryButtonTakesFocusOnFirstComposition() {
        render(state(WELCOME), keyboard = true)
        node(R.string.onboarding_continue).assertIsFocused()
    }

    @Test fun thePrimaryButtonTakesFocusBackOnAPageChange() {
        val ui = render(state(SHIZUKU), keyboard = true)
        node(R.string.onboarding_continue).assertIsFocused()
        node(R.string.onboarding_shizuku_guide)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Focused))
            .performSemanticsAction(SemanticsActions.RequestFocus)
        node(R.string.onboarding_shizuku_guide).assertIsFocused()

        composeTestRule.runOnIdle { ui.value = state(PRIVACY) }
        node(R.string.onboarding_get_started).assertIsFocused()
        node(R.string.onboarding_privacy_policy).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Focused))
    }
}
