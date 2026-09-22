package eu.darken.porter.probe

import eu.darken.porter.sdk.PorterAvailability

/** The token the probe logs for [availability], which the emulator smoke checks match on. */
internal fun availabilityToken(availability: PorterAvailability): String = when (availability) {
    PorterAvailability.NotInstalled -> "NOT_INSTALLED"
    PorterAvailability.InstalledUnrecognized -> "INSTALLED_UNRECOGNIZED"
    PorterAvailability.InstalledNotConnected -> "INSTALLED_NOT_CONNECTED"
    is PorterAvailability.Incompatible -> "INCOMPATIBLE"
    PorterAvailability.Connected -> "CONNECTED"
}
