package eu.darken.porter.manager.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    OnboardingPageBody(page, state.isBeta, actions)
                }
            }
            OnboardingBottomBar(state, actions)
        }
    }
}

@Composable
private fun OnboardingPageBody(page: OnboardingPage, isBeta: Boolean, actions: OnboardingActions) {
    when (page) {
        OnboardingPage.WELCOME -> {
            OnboardingMascot(R.drawable.porter_mascot_happy_large)
            OnboardingTitle(stringResource(R.string.onboarding_welcome_title))
            OnboardingParagraph(stringResource(R.string.onboarding_welcome_body))
            OnboardingParagraph(stringResource(R.string.onboarding_welcome_service))
            if (isBeta) {
                Text(stringResource(R.string.onboarding_welcome_beta), Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
            }
        }
        OnboardingPage.SHIZUKU -> {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.ic_shizuku), contentDescription = null, modifier = Modifier.size(104.dp))
                Image(painterResource(R.drawable.porter_mascot_large), contentDescription = null, modifier = Modifier.size(128.dp))
            }
            OnboardingTitle(stringResource(R.string.onboarding_shizuku_title))
            OnboardingParagraph(stringResource(R.string.onboarding_shizuku_body))
            OnboardingParagraph(stringResource(R.string.onboarding_shizuku_replace))
            OutlinedButton(onClick = actions.onCompatibilityGuide) { Text(stringResource(R.string.onboarding_shizuku_guide)) }
        }
        OnboardingPage.PRIVACY -> {
            OnboardingMascot(R.drawable.porter_mascot_large)
            OnboardingTitle(stringResource(R.string.onboarding_privacy_title))
            OnboardingParagraph(stringResource(R.string.onboarding_privacy_body))
            OutlinedButton(onClick = actions.onPrivacyPolicy) { Text(stringResource(R.string.onboarding_privacy_policy)) }
        }
    }
}

@Composable
private fun OnboardingMascot(mascot: Int) {
    Image(painterResource(mascot), contentDescription = null, modifier = Modifier.size(160.dp))
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
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state.saveFailed) {
            Text(stringResource(R.string.onboarding_save_failed), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium)
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OnboardingPageIndicator(state.pages.size, state.pages.indexOf(state.page))
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
