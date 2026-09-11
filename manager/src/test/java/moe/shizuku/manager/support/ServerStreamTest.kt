package moe.shizuku.manager.support

import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import moe.shizuku.server.IRemoteProcess
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch

/** The recorder's coroutine bodies, whose failure paths the emulator lane cannot force. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerStreamTest {
    @get:Rule val temporary = TemporaryFolder()

    private var destroyed = false
    private fun remote() = object : IRemoteProcess.Stub() {
        override fun getOutputStream(): ParcelFileDescriptor? = null
        override fun getInputStream(): ParcelFileDescriptor? = null
        override fun getErrorStream(): ParcelFileDescriptor? = null
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = 0
        override fun destroy() { destroyed = true }
        override fun alive(): Boolean = true
        override fun waitForTimeout(timeout: Long, unit: String?): Boolean = false
    }
    private fun empty() = ByteArrayInputStream(ByteArray(0))
    private fun completed() = Job().apply { complete() }
    private fun handle(input: InputStream, drain: Job = completed()) =
        ServerDiagnostics.ServerStream(4321, input, empty(), drain, remote())
    private fun events(directory: File) = File(directory, "events.txt").readText()

    @Test fun readerContainsAFailingInput() = runTest {
        val directory = temporary.newFolder()
        val failing = object : InputStream() {
            override fun read(): Int = throw IOException("remote pipe died")
        }
        readServerStream(handle(failing), directory, 1024)
        assertTrue(events(directory).contains("Server stream ended pid=4321"))
        assertTrue(destroyed)
    }

    @Test fun readerContainsAFailingEventAppend() = runTest {
        val directory = temporary.newFolder()
        assertTrue(File(directory, "events.txt").mkdir())
        readServerStream(handle("logcat line\n".byteInputStream()), directory, 1024)
        assertTrue(destroyed)
    }

    @Test fun readerContainsAFailingClose() = runTest {
        val directory = temporary.newFolder()
        val failing = object : ServerDiagnostics.ServerStream(4321, "logcat line\n".byteInputStream(), empty(), completed(), remote()) {
            override suspend fun close() { throw IOException("destroy refused") }
        }
        readServerStream(failing, directory, 1024)
        assertTrue(events(directory).contains("Server stream ended pid=4321"))
    }

    @Test fun readerRethrowsCancellation() = runTest {
        val directory = temporary.newFolder()
        val entered = CompletableDeferred<Unit>()
        val released = CountDownLatch(1)
        val blocking = object : InputStream() {
            override fun read(): Int {
                entered.complete(Unit)
                released.await()
                throw CancellationException("read interrupted")
            }
        }
        var continued = false
        val reader = launch(Dispatchers.IO) {
            readServerStream(handle(blocking), directory, 1024)
            continued = true
        }
        entered.await()
        reader.cancel()
        released.countDown()
        reader.join()
        assertFalse(continued)
        assertTrue(events(directory).contains("Server stream ended pid=4321"))
    }

    @Test fun drainContainsAClosureInducedFailure() = runTest {
        val file = File(temporary.newFolder(), "server-stream.txt")
        val blocked = CompletableDeferred<Unit>()
        val gate = CountDownLatch(1)
        val stream = object : InputStream() {
            private var sent = false
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (!sent) {
                    sent = true
                    return "refused\n".toByteArray().also { it.copyInto(b, off) }.size
                }
                blocked.complete(Unit)
                gate.await()
                throw IOException("stream closed")
            }
            override fun close() = gate.countDown()
        }
        val drain = launch(Dispatchers.IO) { drainServerErrors(stream, file, 1024) }
        blocked.await()
        stream.close()
        drain.join()
        assertEquals("refused\n", file.readText())
    }

    @Test fun drainBoundsTheFileAndStillReachesEof() = runTest {
        val file = File(temporary.newFolder(), "server-stream.txt")
        var exhausted = false
        val source = object : InputStream() {
            private val data = ByteArrayInputStream(ByteArray(4096) { 'e'.code.toByte() })
            override fun read(): Int = data.read().also { if (it < 0) exhausted = true }
            override fun read(b: ByteArray, off: Int, len: Int): Int = data.read(b, off, len).also { if (it < 0) exhausted = true }
        }
        drainServerErrors(source, file, 1024)
        assertEquals(1024L, file.length())
        assertTrue(exhausted)
    }

    @Test fun noDiagnosticsAreWrittenAfterCloseReturns() = runTest {
        val file = File(temporary.newFolder(), "server-stream.txt")
        val started = CompletableDeferred<Unit>()
        val slow = object : InputStream() {
            @Volatile private var open = true
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                started.complete(Unit)
                Thread.sleep(50)
                if (!open) throw IOException("stream closed")
                return "still writing\n".toByteArray().also { it.copyInto(b, off) }.size
            }
            override fun close() { open = false }
        }
        // The remote never exits, so the drain is still running when close() reaches its timeout.
        val drain = backgroundScope.launch(Dispatchers.IO) { drainServerErrors(slow, file, 8L * 1024 * 1024) }
        started.await()
        ServerDiagnostics.ServerStream(4321, empty(), slow, drain, remote()).close()
        val written = file.length()
        assertTrue(drain.isCompleted)
        Thread.sleep(300)
        assertEquals(written, file.length())
    }
}
