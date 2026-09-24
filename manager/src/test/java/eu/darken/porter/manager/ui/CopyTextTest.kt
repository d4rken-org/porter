package eu.darken.porter.manager.ui

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.porter.manager.TestApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class)
class CopyTextTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clip get() = context.getSystemService(ClipboardManager::class.java).primaryClip!!

    @Test @Config(sdk = [34])
    fun aSensitiveCopyIsMarkedSensitive() {
        copyText(context, "secret", sensitive = true)
        assertEquals("secret", clip.getItemAt(0).text)
        assertTrue(clip.description.extras!!.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
    }

    @Test @Config(sdk = [30])
    fun aSensitiveCopyIsMarkedBeforeTheConstantExisted() {
        copyText(context, "secret", sensitive = true)
        assertTrue(clip.description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
    }

    @Test @Config(sdk = [34])
    fun anOrdinaryCopyIsNotMarked() {
        copyText(context, "adb shell")
        assertFalse(clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) ?: false)
    }
}
