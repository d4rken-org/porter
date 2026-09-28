package eu.darken.porter.manager.updater

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import eu.darken.porter.manager.R
import eu.darken.porter.manager.TestApplication
import eu.darken.porter.manager.updater.UpdateInstaller.Operation
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [34])
class UpdateDownloadTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val fixture = UpdateInstallerFixture(application)
    private val document = Uri.parse("content://eu.darken.porter.test.documents/document/porter.apk")

    @Before fun reset() {
        fixture.updates.deleteRecursively()
    }

    /** Fails every write with "disk full", or else accepts the bytes and fails the close with "close failed". */
    private fun failingOutput(failWrite: Boolean) = object : OutputStream() {
        override fun write(b: Int) {
            if (failWrite) throw IOException("disk full")
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (failWrite) throw IOException("disk full")
        }

        override fun close() {
            if (!failWrite) throw IOException("close failed")
        }
    }

    private fun stagedFiles() = fixture.updates.listFiles()?.map { it.name }.orEmpty()

    @Test fun aShortBodyFailsAndLeavesNoFile() = runTest {
        val installer = fixture.installer(backgroundScope)
        installer.installManually(fixture.asset(size = fixture.body.size + 200L))!!.join()
        assertTrue(installer.operation.value is Operation.Failed)
        assertTrue(stagedFiles().isEmpty())
        assertNull(installer.handoff.value)
    }

    @Test fun anOversizedBodyFailsWithoutReadingItAll() = runTest {
        fixture.body = ByteArray(1024 * 1024)
        val installer = fixture.installer(backgroundScope)
        installer.installManually(fixture.asset(size = 100))!!.join()
        assertTrue(installer.operation.value is Operation.Failed)
        assertTrue("consumed ${fixture.consumed}", fixture.consumed <= 100 + UpdateInstaller.BUFFER_SIZE)
        assertTrue(stagedFiles().isEmpty())
        assertNull(installer.handoff.value)
    }

    @Test fun aReleaseWithoutASizeIsNotDownloaded() = runTest {
        val installer = fixture.installer(backgroundScope)
        installer.installManually(fixture.asset(size = 0))!!.join()
        assertTrue(installer.operation.value is Operation.Failed)
        assertEquals(0, fixture.opened)
    }

    @Test fun anHttpErrorFails() = runTest {
        val installer = fixture.installer(backgroundScope) { url ->
            object : HttpURLConnection(URL(url)) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getResponseCode() = 404
                override fun getInputStream(): InputStream = throw IOException("no body")
            }
        }
        installer.installManually(fixture.asset())!!.join()
        assertEquals(Operation.Failed(application.getString(R.string.updater_download_failed, "HTTP 404")), installer.operation.value)
        assertTrue(stagedFiles().isEmpty())
    }

    @Test fun httpDownloadsSetTimeoutsAndNoAcceptHeader() = runTest {
        var connection: HttpURLConnection? = null
        val installer = fixture.installer(backgroundScope) { url ->
            object : HttpURLConnection(URL(url)) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getResponseCode() = 200
                override fun getInputStream(): InputStream = fixture.body.inputStream()
            }.also { connection = it }
        }
        installer.installManually(fixture.asset())!!.join()
        val opened = connection!!
        assertEquals(20_000, opened.connectTimeout)
        assertEquals(60_000, opened.readTimeout)
        assertTrue(opened.instanceFollowRedirects)
        assertNull(opened.getRequestProperty("Accept"))
        assertEquals(Operation.Working(Operation.Kind.INSTALL), installer.operation.value)
    }

    @Test fun aSavedDownloadEndsSaved() = runTest {
        val output = ByteArrayOutputStream()
        shadowOf(application.contentResolver).registerOutputStream(document, output)
        val installer = fixture.installer(backgroundScope)
        installer.save(fixture.asset(), document)!!.join()
        assertEquals(Operation.Saved("porter-0.7.0.apk"), installer.operation.value)
        assertArrayEquals(fixture.body, output.toByteArray())
        assertTrue(fixture.deleted.isEmpty())
    }

    @Test fun aWriteFailureDeletesTheDocument() = runTest {
        shadowOf(application.contentResolver).registerOutputStream(document, failingOutput(failWrite = true))
        val installer = fixture.installer(backgroundScope)
        installer.save(fixture.asset(), document)!!.join()
        assertEquals(Operation.Failed(application.getString(R.string.updater_download_failed, "disk full")), installer.operation.value)
        assertEquals(listOf(document), fixture.deleted)
    }

    @Test fun aCloseFailureDeletesTheDocument() = runTest {
        shadowOf(application.contentResolver).registerOutputStream(document, failingOutput(failWrite = false))
        val installer = fixture.installer(backgroundScope)
        installer.save(fixture.asset(), document)!!.join()
        assertEquals(Operation.Failed(application.getString(R.string.updater_download_failed, "close failed")), installer.operation.value)
        assertEquals(listOf(document), fixture.deleted)
    }

    @Test fun anUndeletedDocumentIsNamedInTheFailure() = runTest {
        shadowOf(application.contentResolver).registerOutputStream(document, failingOutput(failWrite = true))
        fixture.deleteResult = false
        val installer = fixture.installer(backgroundScope)
        installer.save(fixture.asset(), document)!!.join()
        assertEquals(Operation.Failed(application.getString(R.string.updater_download_failed_partial, "disk full")),
            installer.operation.value)
    }

    @Test fun aShortSaveDeletesTheDocument() = runTest {
        shadowOf(application.contentResolver).registerOutputStream(document, ByteArrayOutputStream())
        val installer = fixture.installer(backgroundScope)
        installer.save(fixture.asset(size = fixture.body.size + 1L), document)!!.join()
        assertTrue(installer.operation.value is Operation.Failed)
        assertEquals(listOf(document), fixture.deleted)
    }

    @Test fun staleStagedFilesAreRemovedBeforeTheDownload() = runTest {
        fixture.updates.mkdirs()
        val staleApk = File(fixture.updates, "porter-0.6.0.apk").apply { writeText("old") }
        val stalePart = File(fixture.updates, "porter-0.7.0.apk.part").apply { writeText("partial") }
        var staleAtOpen: Boolean? = null
        fixture.beforeOpen = { staleAtOpen = staleApk.exists() || stalePart.exists() }
        val installer = fixture.installer(backgroundScope)
        installer.installManually(fixture.asset())!!.join()
        assertEquals(false, staleAtOpen)
        assertEquals(listOf("porter-0.7.0.apk"), stagedFiles())
    }

    @Test fun aFileUrlDownloads() = runTest {
        val source = File.createTempFile("porter-update", ".apk").apply { writeBytes(fixture.body); deleteOnExit() }
        val installer = fixture.installer(backgroundScope) { URL(it).openConnection() }
        installer.installManually(fixture.asset(url = source.toURI().toString()))!!.join()
        val staged = installer.takeHandoff()
        assertNotNull(staged)
        assertEquals(File(fixture.updates, "porter-0.7.0.apk"), staged)
        assertArrayEquals(fixture.body, staged!!.readBytes())
        assertFalse(File(fixture.updates, "porter-0.7.0.apk.part").exists())
    }
}
