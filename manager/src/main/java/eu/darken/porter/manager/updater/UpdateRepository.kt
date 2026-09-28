package eu.darken.porter.manager.updater

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import eu.darken.porter.manager.BuildConfig
import eu.darken.porter.manager.PorterSettings
import eu.darken.porter.manager.utils.LOGGER
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

data class AvailableUpdate(val currentVersion: String, val release: Release)

class UpdateRepository @VisibleForTesting internal constructor(
    private val checker: UpdateChecker,
    private val prefs: SharedPreferences,
    private val currentVersion: String,
    private val clock: () -> Long,
    private val scope: CoroutineScope,
) {

    private constructor(context: Context) : this(
        checker = UpdaterFlavor.checker(context),
        prefs = PorterSettings.updaterPreferences,
        currentVersion = BuildConfig.VERSION_NAME,
        clock = System::currentTimeMillis,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    /** The outcome of checking only; download and install progress belongs elsewhere so a check cannot overwrite it. */
    data class State(
        val supported: Boolean,
        val enabled: Boolean,
        val channel: UpdateChannel,
        val update: AvailableUpdate?,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val checkLock = Mutex()
    private val publishLock = Any()
    private val mutable = MutableStateFlow(derive())
    val state: StateFlow<State> = mutable.asStateFlow()

    private fun isEnabled(): Boolean = checker.isSupported && (PorterSettings.updateCheck ?: checker.isEnabledByDefault())

    private fun channel(): UpdateChannel = PorterSettings.updateChannel
        ?: if (currentVersion.contains("-beta")) UpdateChannel.BETA else UpdateChannel.PRODUCTION

    private fun cachedRelease(): Release? = prefs.getString(KEY_RELEASE, null)?.let { text ->
        try {
            json.decodeFromString(Release.serializer(), text)
        } catch (e: Exception) {
            LOGGER.w(e, "Read the cached release")
            null
        }
    }

    private fun derive(): State {
        val enabled = isEnabled()
        val channel = channel()
        val release = if (enabled && prefs.getString(KEY_RELEASE_CHANNEL, null) == channel.key) cachedRelease() else null
        val update = release
            ?.takeIf { ReleaseVersion.isNewer(it.tag, currentVersion) && it.tag != prefs.getString(KEY_DISMISSED_TAG, null) }
            ?.let { AvailableUpdate(currentVersion, it) }
        return State(supported = checker.isSupported, enabled = enabled, channel = channel, update = update)
    }

    /** Every writer stores first and publishes after, so the last publish always sees the latest choice. */
    private fun publish() {
        synchronized(publishLock) { mutable.value = derive() }
    }

    /** Checks unless a recent result or failure makes it pointless; concurrent calls fetch at most once. */
    fun refresh(): Job = scope.launch { check() }

    private suspend fun check() = checkLock.withLock {
        if (!isEnabled()) return@withLock publish()
        val channel = channel()
        val now = clock()
        val cached = prefs.getString(KEY_RELEASE_CHANNEL, null) == channel.key
        if (cached && isRecent(KEY_LAST_CHECK, now, CHECK_INTERVAL)) return@withLock publish()
        if (isRecent(KEY_LAST_ATTEMPT, now, RETRY_INTERVAL)) return@withLock publish()
        val release = try {
            checker.latest(includePrereleases = channel == UpdateChannel.BETA)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOGGER.w(e, "Check for a Porter update")
            prefs.edit().putLong(KEY_LAST_ATTEMPT, clock()).apply()
            return@withLock publish()
        }
        prefs.edit()
            .putString(KEY_RELEASE, release?.let { json.encodeToString(Release.serializer(), it) })
            .putString(KEY_RELEASE_CHANNEL, channel.key)
            .putLong(KEY_LAST_CHECK, clock())
            .remove(KEY_LAST_ATTEMPT)
            .apply()
        publish()
    }

    /** A stamp from the future means the clock moved back, and counts as long ago. */
    private fun isRecent(key: String, now: Long, interval: Long): Boolean {
        if (!prefs.contains(key)) return false
        val at = prefs.getLong(key, 0L)
        return at <= now && now - at < interval
    }

    /** Returns the check it starts, or null when there is nothing to check. */
    fun setEnabled(enabled: Boolean): Job? {
        PorterSettings.updateCheck = enabled
        publish()
        return if (enabled) refresh() else null
    }

    /** Returns the check it starts, or null when checks are off. */
    fun setChannel(channel: UpdateChannel): Job? {
        PorterSettings.updateChannel = channel
        publish()
        return if (isEnabled()) refresh() else null
    }

    /** Hides the offered release until a different one comes along. */
    fun dismiss() {
        synchronized(publishLock) {
            val tag = mutable.value.update?.release?.tag ?: return
            prefs.edit().putString(KEY_DISMISSED_TAG, tag).apply()
            mutable.value = derive()
        }
    }

    companion object {
        private const val KEY_LAST_CHECK = "last_check"
        private const val KEY_LAST_ATTEMPT = "last_attempt"
        private const val KEY_RELEASE = "release"
        private const val KEY_RELEASE_CHANNEL = "release_channel"
        private const val KEY_DISMISSED_TAG = "dismissed_tag"
        private const val CHECK_INTERVAL = 24 * 60 * 60 * 1000L
        private const val RETRY_INTERVAL = 60 * 60 * 1000L

        @Volatile private var instance: UpdateRepository? = null

        fun get(context: Context): UpdateRepository = instance ?: synchronized(this) {
            instance ?: UpdateRepository(context.applicationContext).also { instance = it }
        }
    }
}
