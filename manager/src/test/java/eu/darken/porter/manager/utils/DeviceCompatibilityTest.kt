package eu.darken.porter.manager.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCompatibilityTest {

    @Test fun `miui and hyperos build numbers are xiaomi roms`() {
        for ((manufacturer, incremental) in listOf(
            "Xiaomi" to "V11.0.8.0.PCBMIXM",
            "POCO" to "V816.0.1.0.UMNMIXM",
            "Xiaomi" to "OS2.0.6.0.VMLMIXM",
            "POCO" to "OS3.0.3.0.WOMEUXM",
            "xiaomi" to "OS4.0.1.0.WNLCNXM",
        )) {
            assertTrue("$manufacturer $incremental", DeviceCompatibility.isXiaomiRom(manufacturer, incremental))
        }
    }

    @Test fun `other build numbers or manufacturers are not`() {
        for ((manufacturer, incremental) in listOf(
            "Xiaomi" to "eng.nobody.20250101.000000",
            "Xiaomi" to "OS2custom",
            "Google" to "V11.0.8.0.PCBMIXM",
        )) {
            assertFalse("$manufacturer $incremental", DeviceCompatibility.isXiaomiRom(manufacturer, incremental))
        }
    }
}
