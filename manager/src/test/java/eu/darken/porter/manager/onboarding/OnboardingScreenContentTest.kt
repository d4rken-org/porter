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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import eu.darken.porter.manager.ComposeTest
import eu.darken.porter.manager.R
import eu.darken.porter.manager.onboarding.OnboardingPage.PRIVACY
import eu.darken.porter.manager.onboarding.OnboardingPage.SHIZUKU
import eu.darken.porter.manager.onboarding.OnboardingPage.WELCOME
import eu.darken.porter.manager.ui.PorterTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        onUpdateCheckChange = { clicks += "updateCheck=$it" },
    )

    private fun state(page: OnboardingPage, isBeta: Boolean = false, finishing: Boolean = false, saveFailed: Boolean = false,
                      updateCheckSupported: Boolean = false, updateCheck: Boolean = false) =
        OnboardingUiState(listOf(WELCOME, SHIZUKU, PRIVACY), page, isBeta, finishing, saveFailed, updateCheckSupported, updateCheck)

    private var backDispatcher: OnBackPressedDispatcher? = null

    private fun render(initial: OnboardingUiState, fontScale: Float? = null, keyboard: Boolean = false,
                       layoutDirection: LayoutDirection = LayoutDirection.Ltr): MutableState<OnboardingUiState> {
        val ui = mutableStateOf(initial)
        composeTestRule.setContent {
            if (keyboard) {
                val inputMode = LocalInputModeManager.current
                SideEffect { inputMode.requestInputMode(InputMode.Keyboard) }
            }
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale ?: density.fontScale),
                LocalLayoutDirection provides layoutDirection,
            ) {
                PorterTheme(dark = false) { OnboardingScreenContent(ui.value, actions) }
            }
        }
        return ui
    }

    private fun node(id: Int) = composeTestRule.onNodeWithText(string(id))

    private fun lineCount(id: Int): Int {
        val layouts = mutableListOf<TextLayoutResult>()
        composeTestRule.onNodeWithText(string(id), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single().lineCount
    }

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

    @Test fun thePrivacyPageShowsTheUpdateCheckAsSet() {
        val ui = render(state(PRIVACY, updateCheckSupported = true, updateCheck = true))
        node(R.string.updater_check).performScrollTo().assertIsDisplayed().assertIsOn()

        composeTestRule.runOnIdle { ui.value = state(PRIVACY, updateCheckSupported = true, updateCheck = false) }
        node(R.string.updater_check).assertIsOff()
    }

    @Test fun theUpdateCheckSwitchReportsItsChange() {
        render(state(PRIVACY, updateCheckSupported = true, updateCheck = true))
        node(R.string.updater_check).performScrollTo().performClick()
        assertEquals(listOf("updateCheck=false"), clicks)
    }

    @Test fun anUnsupportedUpdateCheckHasNoSwitch() {
        render(state(PRIVACY, updateCheckSupported = false, updateCheck = true))
        node(R.string.onboarding_privacy_policy).assertIsDisplayed()
        node(R.string.updater_check).assertDoesNotExist()
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

        composeTestRule.runOnIdle { ui.value = state(PRIVACY, updateCheckSupported = true, updateCheck = true) }
        node(R.string.onboarding_get_started).assertIsDisplayed()
        node(R.string.updater_check_summary).performScrollTo().assertIsDisplayed()
        node(R.string.onboarding_get_started).assertIsDisplayed()
    }

    @Config(qualifiers = "w320dp-h640dp")
    @Test fun aNarrowLargeFontPortraitReachesTheUpdateCheck() {
        render(state(PRIVACY, updateCheckSupported = true, updateCheck = true), fontScale = 2f)
        node(R.string.onboarding_get_started).assertIsDisplayed()
        node(R.string.updater_check_summary).performScrollTo().assertIsDisplayed()
        node(R.string.updater_check).assertIsDisplayed().performClick()
        node(R.string.onboarding_get_started).assertIsDisplayed()
        assertEquals(listOf("updateCheck=false"), clicks)
    }

    @Config(qualifiers = "w320dp-h640dp")
    @Test fun aNarrowLargeFontKeepsTheButtonLabelOnOneLine() {
        val ui = render(state(WELCOME), fontScale = 1.5f)
        node(R.string.onboarding_continue).assertIsDisplayed()
        assertEquals(1, lineCount(R.string.onboarding_continue))

        composeTestRule.runOnIdle { ui.value = state(PRIVACY, updateCheckSupported = true, updateCheck = true) }
        node(R.string.onboarding_get_started).assertIsDisplayed()
        assertEquals(1, lineCount(R.string.onboarding_get_started))
    }

    @Test fun theIndicatorIsCenteredWhenThereIsRoom() {
        render(state(PRIVACY))
        val root = composeTestRule.onRoot().getBoundsInRoot()
        val indicator = composeTestRule.onNodeWithTag(PAGE_INDICATOR_TAG).getBoundsInRoot()
        assertEquals((root.left + root.right).value / 2, (indicator.left + indicator.right).value / 2, 1f)
    }

    @Config(qualifiers = "w320dp-h640dp")
    @Test fun aNarrowLargeFontMovesTheIndicatorAwayFromTheButton() {
        render(state(PRIVACY), fontScale = 1.5f)
        val indicator = composeTestRule.onNodeWithTag(PAGE_INDICATOR_TAG).getBoundsInRoot()
        val button = node(R.string.onboarding_get_started).getBoundsInRoot()
        assertTrue("$indicator overlaps $button", indicator.right + 16.dp <= button.left)
    }

    @Config(qualifiers = "w320dp-h640dp")
    @Test fun rightToLeftPutsTheButtonAtTheStart() {
        render(state(PRIVACY), fontScale = 1.5f, layoutDirection = LayoutDirection.Rtl)
        val indicator = composeTestRule.onNodeWithTag(PAGE_INDICATOR_TAG).getBoundsInRoot()
        val button = node(R.string.onboarding_get_started).getBoundsInRoot()
        assertTrue("$button overlaps $indicator", button.right + 16.dp <= indicator.left)
        assertEquals(1, lineCount(R.string.onboarding_get_started))
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
