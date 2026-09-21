package eu.darken.porter.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.StringReader

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ShizukuConfigManagerTest {

    private fun read(json: String) = ShizukuConfigManager.read(StringReader(json))

    @Test
    fun theCurrentVersionLoads() {
        val config = read("""{"version":2,"accessPaused":true,"packages":[{"uid":10123,"flags":1,"packages":["example"]}]}""")
        assertTrue(config.accessPaused)
        assertEquals(listOf(10123), config.packages!!.map { it.uid })
    }

    @Test
    fun otherVersionsStartEmpty() {
        val entry = """[{"uid":10123,"flags":1,"packages":["example"]}]"""
        for (json in listOf(
            """{"version":1,"packages":$entry}""",
            """{"version":3,"accessPaused":true,"packages":$entry}""",
            """{"version":2.5,"accessPaused":true,"packages":$entry}""",
            """{"version":"2","accessPaused":true,"packages":$entry}""",
            """{"version":null,"accessPaused":true,"packages":$entry}""",
            """{"accessPaused":true,"packages":$entry}""",
            """[]""",
            "",
        )) {
            val config = read(json)
            assertEquals(json, ShizukuConfig.LATEST_VERSION, config.version)
            assertEquals(json, false, config.accessPaused)
            assertTrue(json, config.packages!!.isEmpty())
        }
    }
}
