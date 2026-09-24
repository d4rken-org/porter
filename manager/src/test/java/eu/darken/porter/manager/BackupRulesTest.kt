package eu.darken.porter.manager

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import eu.darken.porter.manager.adb.PreferenceAdbKeyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [34])
class BackupRulesTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()

    private fun store(name: String): SharedPreferences = application.createDeviceProtectedStorageContext()
        .getSharedPreferences(name, Context.MODE_PRIVATE)

    /**
     * Every `include` in one rules file, keyed by the section it sits in.
     *
     *     <cloud-backup><include domain="device_sharedpref" path="settings.xml" /></cloud-backup>
     *     -> {"cloud-backup": ["device_sharedpref/settings.xml"]}
     */
    private fun includes(xmlRes: Int): Map<String, Set<String>> {
        val sections = mutableMapOf<String, MutableSet<String>>()
        val parser = application.resources.getXml(xmlRes)
        var section = ""
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            if (parser.name != "include") {
                section = parser.name
                continue
            }
            val domain = parser.getAttributeValue(null, "domain")
            val path = parser.getAttributeValue(null, "path")
            sections.getOrPut(section) { mutableSetOf() }.add("$domain/$path")
        }
        return sections
    }

    private fun allIncludes(): Set<String> =
        (includes(R.xml.backup_descriptor) + includes(R.xml.data_extraction_rules)).values.flatten().toSet()

    @Before fun resetSettings() {
        store(PorterSettings.NAME).edit().clear().commit()
        store(PorterSettings.SECRETS_NAME).edit().clear().commit()
        PorterSettings.resetForTest()
    }

    /** Both rule files name one file, so anything added to either one has to be decided on. */
    @Test fun theBackupRulesCarryOnlyTheSettingsFile() {
        val settings = setOf("device_sharedpref/${PorterSettings.NAME}.xml")
        assertEquals(mapOf("full-backup-content" to settings), includes(R.xml.backup_descriptor))
        assertEquals(
            mapOf("cloud-backup" to settings, "device-transfer" to settings),
            includes(R.xml.data_extraction_rules),
        )
    }

    /** What keeps the auth token off the wire is the file it lives in, not a per-key exclusion. */
    @Test fun theRefusalsFileIsNeverBackedUp() {
        assertTrue(allIncludes().none { it.endsWith("/${PorterSettings.REFUSALS_NAME}.xml") })
    }

    /** A boot count restored onto another device could match a boot there and suppress its start. */
    @Test fun theBootFileIsNeverBackedUp() {
        assertTrue(allIncludes().none { it.endsWith("/${PorterSettings.BOOT_NAME}.xml") })
    }

    @Test fun theSecretsFileIsNeverBackedUp() {
        assertTrue(allIncludes().none { it.endsWith("/${PorterSettings.SECRETS_NAME}.xml") })
    }

    /**
     * The ADB key is the one value deliberately left in the backed-up file: it is ciphertext under
     * an AndroidKeyStore key that no backup carries, so a restore cannot use it. Moving it would
     * change nothing a user sees, and this failing is the place to weigh that again.
     */
    @Test fun theAdbKeyStaysInTheBackedUpSettingsOnPurpose() {
        PorterSettings.initialize(application)
        PreferenceAdbKeyStore(PorterSettings.preferences).put(byteArrayOf(1, 2, 3))
        assertTrue(store(PorterSettings.NAME).contains("adbkey"))
        assertTrue(allIncludes().contains("device_sharedpref/${PorterSettings.NAME}.xml"))
    }
}
