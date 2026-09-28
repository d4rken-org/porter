package eu.darken.porter.manager.settings

import android.content.Context
import eu.darken.porter.manager.NotificationChannels
import eu.darken.porter.manager.PorterSettings
import eu.darken.porter.manager.utils.EnvironmentUtils
import eu.darken.porter.manager.utils.NotificationAlerts
import eu.darken.porter.manager.utils.SettingsHelper

/**
 * What the Startup screen's two conditional rows report. Home, the settings index and Startup all
 * answer from here, so none of them can flag a problem the others do not show.
 */
internal object StartupAttention {

    fun batteryRowNeeded(
        isTelevision: Boolean,
        ignoringBatteryOptimizations: Boolean,
        startOnBoot: Boolean,
        watchdog: Boolean,
    ): Boolean = !isTelevision && !ignoringBatteryOptimizations && (startOnBoot || watchdog)

    /**
     * Whether an enabled feature currently has no way to reach the user. The crash channel is never
     * asked; `StartupAttentionTest` pins that.
     */
    fun alertsBlocked(startOnBoot: Boolean, watchdog: Boolean, canAlert: (channel: String) -> Boolean): Boolean =
        (startOnBoot && !canAlert(NotificationChannels.ADB_START)) ||
            (watchdog && !canAlert(NotificationChannels.WATCHDOG))

    fun batteryRowNeeded(context: Context): Boolean = batteryRowNeeded(
        isTelevision = EnvironmentUtils.isTelevision(),
        ignoringBatteryOptimizations = SettingsHelper.isIgnoringBatteryOptimizations(context),
        startOnBoot = PorterSettings.isStartOnBoot(context),
        watchdog = PorterSettings.watchdog,
    )

    fun alertsBlocked(context: Context): Boolean =
        alertsBlocked(PorterSettings.isStartOnBoot(context), PorterSettings.watchdog) { NotificationAlerts.canAlert(context, it) }

    fun needsAttention(context: Context): Boolean = batteryRowNeeded(context) || alertsBlocked(context)
}
