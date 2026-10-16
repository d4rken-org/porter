package eu.darken.porter.manager.home

import org.junit.Assert.assertEquals
import org.junit.Test

class AccessibilityDialogStepTest {

    private val sideloaded = "com.google.android.packageinstaller"

    private fun step(
        enabled: Boolean = false,
        canWriteSecureSettings: Boolean = false,
        foss: Boolean = true,
        installer: String? = "com.android.vending",
        sdkInt: Int = 35,
    ) = accessibilityDialogStep(enabled, canWriteSecureSettings, foss, installer, sdkInt)

    @Test fun anEnabledServiceGoesStraightToPairing() {
        assertEquals("navigate", step(enabled = true))
        assertEquals("navigate", step(enabled = true, canWriteSecureSettings = true, foss = false))
        assertEquals("navigate", step(enabled = true, foss = false, installer = sideloaded, sdkInt = 34))
    }

    @Test fun theFossBuildTurnsTheServiceOnWithSecureSettings() {
        assertEquals("self-enable", step(canWriteSecureSettings = true))
        assertEquals("self-enable", step(canWriteSecureSettings = true, installer = sideloaded, sdkInt = 34))
    }

    @Test fun thePlayBuildAlwaysShowsTheDisclosureFirst() {
        assertEquals("enable", step(canWriteSecureSettings = true, foss = false))
        assertEquals("enable", step(foss = false))
        assertEquals("enable", step(foss = false, canWriteSecureSettings = true, installer = sideloaded, sdkInt = 32))
    }

    @Test fun aSideloadedInstallOnAndroid13And14NeedsRestrictedSettings() {
        assertEquals("permission", step(installer = sideloaded, sdkInt = 33))
        assertEquals("permission", step(installer = sideloaded, sdkInt = 34))
        assertEquals("permission", step(foss = false, canWriteSecureSettings = true, installer = sideloaded, sdkInt = 34))
        assertEquals("enable", step(installer = sideloaded, sdkInt = 32))
        assertEquals("enable", step(installer = sideloaded, sdkInt = 35))
        assertEquals("enable", step(installer = null, sdkInt = 34))
        assertEquals("enable", step(foss = false, sdkInt = 34))
    }
}
