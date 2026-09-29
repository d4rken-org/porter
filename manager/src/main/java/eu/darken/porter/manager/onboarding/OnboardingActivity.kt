package eu.darken.porter.manager.onboarding

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import eu.darken.porter.manager.BuildConfig
import eu.darken.porter.manager.MainActivity
import eu.darken.porter.manager.PorterSettings
import eu.darken.porter.manager.isBetaVersion
import eu.darken.porter.manager.ui.ComposeActivity
import eu.darken.porter.manager.utils.CustomTabsHelper
import kotlinx.coroutines.launch

class OnboardingActivity : ComposeActivity() {
    private val model: OnboardingViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (PorterSettings.onboardingCompleted) {
            openHome()
            return
        }
        porterContent { OnboardingScreen() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                for (event in model.events) {
                    when (event) {
                        OnboardingEvent.OpenHome -> openHome()
                        OnboardingEvent.Close -> finish()
                        is OnboardingEvent.OpenUrl -> CustomTabsHelper.launchUrlOrCopy(this@OnboardingActivity, event.url)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!isFinishing && PorterSettings.onboardingCompleted) openHome()
    }

    private fun openHome() {
        if (isFinishing) return
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    @Composable
    private fun OnboardingScreen() {
        val page by model.page.collectAsStateWithLifecycle()
        val finishing by model.finishing.collectAsStateWithLifecycle()
        val saveFailed by model.saveFailed.collectAsStateWithLifecycle()
        val updateState by model.updateState.collectAsStateWithLifecycle()
        OnboardingScreenContent(
            OnboardingUiState(
                pages = model.pages,
                page = page,
                isBeta = isBetaVersion(BuildConfig.VERSION_NAME),
                finishing = finishing,
                saveFailed = saveFailed,
                updateCheckSupported = updateState.supported,
                updateCheck = updateState.enabled,
            ),
            OnboardingActions(
                onNext = model::next,
                onBack = model::back,
                onFinish = model::finish,
                onPrivacyPolicy = model::openPrivacyPolicy,
                onCompatibilityGuide = model::openCompatibilityGuide,
                onUpdateCheckChange = model::setUpdateCheck,
            ),
        )
    }
}
