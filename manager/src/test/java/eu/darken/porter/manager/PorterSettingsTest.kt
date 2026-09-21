package eu.darken.porter.manager

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [34])
class PorterSettingsTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()

    private fun store(name: String): SharedPreferences = application.createDeviceProtectedStorageContext()
        .getSharedPreferences(name, Context.MODE_PRIVATE)

    @Before fun resetSettings() {
        store(PorterSettings.NAME).edit().clear().commit()
        store(PorterSettings.SECRETS_NAME).edit().clear().commit()
        PorterSettings.resetForTest()
    }

    @Test fun initializingStampsTheSchemaVersion() {
        PorterSettings.initialize(application)
        assertEquals(PorterSettings.SCHEMA_VERSION, store(PorterSettings.NAME).getInt(PorterSettings.Keys.KEY_SCHEMA_VERSION, 0))
    }

    /** The backup rules include settings.xml and exclude secrets.xml, so the token must stay in the latter. */
    @Test fun theAuthTokenLivesOutsideTheBackedUpSettings() {
        PorterSettings.initialize(application)
        val token = PorterSettings.authToken
        assertTrue(token.isNotEmpty())
        assertEquals(token, store(PorterSettings.SECRETS_NAME).getString(PorterSettings.Keys.KEY_AUTH_TOKEN, null))
        assertFalse(store(PorterSettings.NAME).contains(PorterSettings.Keys.KEY_AUTH_TOKEN))
        assertEquals(token, PorterSettings.authToken)
    }
}
