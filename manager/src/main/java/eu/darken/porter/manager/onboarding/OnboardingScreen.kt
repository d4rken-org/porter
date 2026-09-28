package eu.darken.porter.manager.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.darken.porter.manager.R

internal data class OnboardingUiState(
    val pages: List<OnboardingPage>,
    val page: OnboardingPage,
    val isBeta: Boolean,
    val finishing: Boolean = false,
    val saveFailed: Boolean = false,
)

internal data class OnboardingActions(
    val onNext: () -> Unit,
    val onBack: () -> Unit,
    val onFinish: () -> Unit,
    val onPrivacyPolicy: () -> Unit,
    val onCompatibilityGuide: () -> Unit,
)

@Composable
internal fun OnboardingScreenContent(state: OnboardingUiState, actions: OnboardingActions, modifier: Modifier = Modifier) {
    BackHandler(onBack = actions.onBack)
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            AnimatedContent(state.page, Modifier.weight(1f), label = "onboardingPage") { page ->
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    OnboardingPageBody(page, state.isBeta)
                }
            }
            OnboardingBottomBar(state, actions)
        }
    }
}

@Composable
private fun OnboardingPageBody(page: OnboardingPage, isBeta: Boolean) {
    val mascot = if (page == OnboardingPage.WELCOME) R.drawable.porter_mascot_happy_large else R.drawable.porter_mascot_large
    Image(painterResource(mascot), contentDescription = null, modifier = Modifier.size(160.dp))
    when (page) {
        OnboardingPage.WELCOME -> {
            OnboardingTitle(stringResource(R.string.onboarding_welcome_title))
            OnboardingParagraph(stringResource(R.string.onboarding_welcome_body))
            OnboardingParagraph(stringResource(R.string.onboarding_welcome_service))
            if (isBeta) {
                Text(stringResource(R.string.onboarding_welcome_beta), Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
            }
        }
        OnboardingPage.SHIZUKU -> {
            OnboardingTitle(stringResource(R.string.onboarding_shizuku_title))
            OnboardingParagraph(stringResource(R.string.onboarding_shizuku_body))
            OnboardingParagraph(stringResource(R.string.onboarding_shizuku_replace))
        }
        OnboardingPage.PRIVACY -> {
            OnboardingTitle(stringResource(R.string.onboarding_privacy_title))
            OnboardingParagraph(stringResource(R.string.onboarding_privacy_body))
        }
    }
}

@Composable
private fun OnboardingTitle(text: String) {
    Text(text, Modifier.fillMaxWidth().semantics { heading() }, style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center)
}

@Composable
private fun OnboardingParagraph(text: String) {
    Text(text, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OnboardingBottomBar(state: OnboardingUiState, actions: OnboardingActions) {
    val primaryFocus = remember { FocusRequester() }
    val inputMode = LocalInputModeManager.current.inputMode
    LaunchedEffect(state.page, inputMode) {
        if (inputMode == InputMode.Keyboard) {
            try { primaryFocus.requestFocus() } catch (_: IllegalStateException) {}
        }
    }
    val last = state.page == OnboardingPage.PRIVACY
    val secondary = when (state.page) {
        OnboardingPage.WELCOME -> null
        OnboardingPage.SHIZUKU -> stringResource(R.string.onboarding_shizuku_guide) to actions.onCompatibilityGuide
        OnboardingPage.PRIVACY -> stringResource(R.string.onboarding_privacy_policy) to actions.onPrivacyPolicy
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state.saveFailed) {
            Text(stringResource(R.string.onboarding_save_failed), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium)
        }
        OnboardingPageIndicator(state.pages.size, state.pages.indexOf(state.page))
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (secondary != null) {
                val (label, onClick) = secondary
                OutlinedButton(onClick = onClick) { Text(label) }
            }
            Button(
                onClick = if (last) actions.onFinish else actions.onNext,
                enabled = !(last && state.finishing),
                modifier = Modifier.focusRequester(primaryFocus),
            ) {
                Text(stringResource(if (last) R.string.onboarding_get_started else R.string.onboarding_continue))
            }
        }
    }
}

@Composable
private fun OnboardingPageIndicator(count: Int, current: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(count) { index ->
            val color = if (index == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
            Box(Modifier.size(8.dp).background(color, CircleShape))
        }
    }
}
