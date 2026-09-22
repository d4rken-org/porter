package eu.darken.porter.manager.settings

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import eu.darken.porter.manager.NotificationChannels
import eu.darken.porter.manager.PorterSettings
import eu.darken.porter.manager.utils.EnvironmentUtils
import eu.darken.porter.manager.utils.NotificationAlerts
import eu.darken.porter.manager.utils.SettingsHelper

class SettingsViewModel @JvmOverloads constructor(
    application: Application,
    private val savedState: SavedStateHandle,
    private val alertProbe: (Context, String) -> Boolean = { context, channel -> NotificationAlerts.canAlert(context, channel) },
    private val isTelevision: () -> Boolean = { EnvironmentUtils.isTelevision() },
    /** Where the blocking part of a toggle runs; a test replaces it to make the write observable. */
    private val io: CoroutineContext = Dispatchers.IO,
    private val rootProbe: suspend () -> Boolean = { withContext(Dispatchers.IO) { EnvironmentUtils.isRooted() } },
) : AndroidViewModel(application) {
    val dialog: StateFlow<String?> = savedState.getStateFlow("dialog", null)
    val pendingSetting: StateFlow<String?> = savedState.getStateFlow("pendingSetting", null)

    /**
     * Whether this screen is waiting to be returned to from the notification settings, which come
     * back with no result. Kept here rather than in the activity: the activity is what a rotation
     * destroys, and losing this while [pendingSetting] survives leaves a toggle that can never be
     * finished.
     */
    val awaitingAlerts: StateFlow<Boolean> = savedState.getStateFlow("awaitingAlerts", false)

    fun awaitAlertsChoice() { savedState["awaitingAlerts"] = true }

    private val bootCapable = MutableStateFlow<Boolean?>(null)

    private val saving = MutableStateFlow(false)

    /** True while a toggle's write is on its way to disk, which the switches are disabled for. */
    val busy: StateFlow<Boolean> = saving.asStateFlow()

    /** `null` until the probe below resolves, which leaves the start-on-boot toggle disabled. */
    val canBoot: StateFlow<Boolean?> = bootCapable.asStateFlow()

    init {
        // The root probe blocks on shell initialisation and can wait on a root prompt, so the
        // cheap answers are taken first and the probe itself never runs on a UI thread.
        if (Build.VERSION.SDK_INT >= 30 || EnvironmentUtils.isTelevision()) {
            bootCapable.value = true
        } else {
            viewModelScope.launch {
                bootCapable.value = try {
                    rootProbe()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    false
                }
            }
        }
    }

    fun show(value: String?) { savedState["dialog"] = value }

    fun setAutoUpdateService(enabled: Boolean) {
        if (eu.darken.porter.manager.utils.UserHandleCompat.myUserId() != 0) return
        PorterSettings.preferences.edit().putBoolean(PorterSettings.Keys.KEY_AUTO_UPDATE_SERVICE, enabled).apply()
        if (!enabled) eu.darken.porter.manager.worker.ServiceUpdateWorker.cancel(getApplication())
    }

    fun toggle(key: String, enabled: Boolean) {
        // A toggle is in flight and its disk write has not returned; taking a second one now
        // would overwrite the key that write is about to clear.
        if (saving.value) return
        savedState["pendingSetting"] = key
        if (enabled && key == PorterSettings.Keys.KEY_START_ON_BOOT && !isTelevision() && Build.VERSION.SDK_INT < 33) {
            show("boot_warning")
        } else checkBattery(enabled)
    }

    fun checkBattery(enabled: Boolean = true) {
        if (enabled && !isTelevision() && !SettingsHelper.isIgnoringBatteryOptimizations(getApplication())) {
            show("battery")
        } else checkAlerts(enabled)
    }

    fun batteryResult() {
        if (SettingsHelper.isIgnoringBatteryOptimizations(getApplication())) checkAlerts(true)
        else cancelToggle()
    }

    /**
     * Both of these features report only through notifications: start-on-boot has nothing but the
     * "waiting for wifi" and retry messages to say why no service came back, and the watchdog's own
     * notification is where its off switch lives. Enabling either while those are muted is allowed,
     * but not silently.
     */
    fun checkAlerts(enabled: Boolean = true) {
        if (enabled && !alertsAvailable()) show("alerts") else applyToggle(enabled)
    }

    /** The user was told the alerts are muted and asked for the setting anyway. */
    fun applyWithoutAlerts() = applyToggle(true)

    /** Back from the notification settings: take the toggle only if the block is actually gone. */
    fun alertsResult() {
        if (pendingSetting.value == null) return
        if (alertsAvailable()) applyToggle(true) else cancelToggle()
    }

    /**
     * The crash channel is deliberately absent: its own notification offers turning it off, so a
     * user who took that action must not be told the watchdog is now misconfigured.
     */
    private fun alertsAvailable(): Boolean {
        val channel = when (pendingSetting.value) {
            PorterSettings.Keys.KEY_START_ON_BOOT -> NotificationChannels.ADB_START
            PorterSettings.Keys.KEY_WATCHDOG -> NotificationChannels.WATCHDOG
            else -> return true
        }
        return alertProbe(getApplication(), channel)
    }

    fun cancelToggle() { clearPending(); show(null) }

    private fun clearPending() {
        savedState["pendingSetting"] = null
        savedState["awaitingAlerts"] = false
    }

    /**
     * Off the main thread, because [PorterSettings.setStartOnBoot] writes to disk before it
     * returns. The key is read once, here, so a toggle arriving while this one is on its way
     * cannot redirect it; [toggle] refuses that toggle anyway.
     */
    private fun applyToggle(enabled: Boolean) {
        // Guarded here and not only in [toggle]: the dialogs on the way to this stay on screen
        // with their buttons live until the write finishes, so a second confirmation would start
        // a second write whose completion clears the first one's state.
        if (saving.value) return
        val key = pendingSetting.value ?: return cancelToggle()
        saving.value = true
        show(null)
        viewModelScope.launch {
            try {
                withContext(io) {
                    when (key) {
                        PorterSettings.Keys.KEY_START_ON_BOOT -> PorterSettings.setStartOnBoot(getApplication(), enabled)
                        PorterSettings.Keys.KEY_WATCHDOG -> PorterSettings.setWatchdog(getApplication(), enabled)
                    }
                }
            } finally {
                saving.value = false
                // Only this toggle's own state: the dialog it was started from is already gone,
                // and whatever is on screen by now belongs to whatever the user did next.
                clearPending()
            }
        }
    }
}
