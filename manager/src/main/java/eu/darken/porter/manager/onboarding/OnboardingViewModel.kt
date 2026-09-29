package eu.darken.porter.manager.onboarding

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import eu.darken.porter.manager.Helps
import eu.darken.porter.manager.PorterSettings
import eu.darken.porter.manager.compatibility.OtherShizukuDetector
import eu.darken.porter.manager.updater.UpdateRepository
import eu.darken.porter.manager.utils.LOGGER
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class OnboardingPage { WELCOME, SHIZUKU, PRIVACY }

internal sealed interface OnboardingEvent {
    data object OpenHome : OnboardingEvent
    data object Close : OnboardingEvent
    data class OpenUrl(val url: String) : OnboardingEvent
}

internal class OnboardingViewModel @JvmOverloads constructor(
    application: Application,
    private val savedState: SavedStateHandle,
    detectShizuku: (Context) -> Boolean = { OtherShizukuDetector.isInstalled(it) },
    private val markCompleted: () -> Boolean = { PorterSettings.markOnboardingCompleted() },
    private val io: CoroutineContext = Dispatchers.IO,
    private val updates: UpdateRepository = UpdateRepository.get(application),
) : AndroidViewModel(application) {

    /**
     * Fixed for the life of the flow: the answer from the first creation is kept, so a page does
     * not appear or vanish under the user when the activity is recreated.
     */
    val pages: List<OnboardingPage>

    init {
        if (!savedState.contains(KEY_SHIZUKU)) savedState[KEY_SHIZUKU] = detectShizuku(application)
        val showShizuku = savedState.get<Boolean>(KEY_SHIZUKU) == true
        pages = OnboardingPage.entries.filter { it != OnboardingPage.SHIZUKU || showShizuku }
    }

    private val currentPage = MutableStateFlow(
        savedState.get<String>(KEY_PAGE)?.let { name -> pages.firstOrNull { it.name == name } } ?: pages.first()
    )
    val page: StateFlow<OnboardingPage> = currentPage.asStateFlow()

    private val mutableFinishing = MutableStateFlow(false)
    val finishing: StateFlow<Boolean> = mutableFinishing.asStateFlow()

    private val mutableSaveFailed = MutableStateFlow(false)
    val saveFailed: StateFlow<Boolean> = mutableSaveFailed.asStateFlow()

    val updateState: StateFlow<UpdateRepository.State> get() = updates.state

    private val eventChannel = Channel<OnboardingEvent>(Channel.BUFFERED)
    val events: ReceiveChannel<OnboardingEvent> get() = eventChannel

    private fun show(target: OnboardingPage) {
        currentPage.value = target
        savedState[KEY_PAGE] = target.name
        mutableSaveFailed.value = false
    }

    fun next() {
        val index = pages.indexOf(currentPage.value)
        if (index < pages.lastIndex) show(pages[index + 1])
    }

    fun back() {
        val index = pages.indexOf(currentPage.value)
        if (index > 0) show(pages[index - 1]) else eventChannel.trySend(OnboardingEvent.Close)
    }

    fun openPrivacyPolicy() {
        eventChannel.trySend(OnboardingEvent.OpenUrl(Helps.PRIVACY))
    }

    fun setUpdateCheck(enabled: Boolean) {
        updates.setEnabled(enabled, checkNow = false)
    }

    fun openCompatibilityGuide() {
        eventChannel.trySend(OnboardingEvent.OpenUrl(Helps.APPS.get()))
    }

    fun finish() {
        if (mutableFinishing.value) return
        mutableFinishing.value = true
        mutableSaveFailed.value = false
        viewModelScope.launch {
            val saved = try {
                withContext(io) { markCompleted() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOGGER.e(e, "Storing onboarding completion failed")
                false
            }
            if (saved) {
                eventChannel.send(OnboardingEvent.OpenHome)
            } else {
                mutableFinishing.value = false
                mutableSaveFailed.value = true
            }
        }
    }

    private companion object {
        const val KEY_SHIZUKU = "showShizuku"
        const val KEY_PAGE = "page"
    }
}
