package eu.darken.porter.manager.support

import android.os.IBinder
import android.os.ParcelFileDescriptor
import eu.darken.porter.server.IPorterRemoteProcess
import kotlinx.coroutines.Job
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A pipe that stays open, delivering nothing, until it is closed. */
internal class BlockingInput : InputStream() {
    private val closed = CountDownLatch(1)
    val isClosed get() = closed.count == 0L
    override fun read(): Int {
        closed.await()
        return -1
    }
    override fun read(b: ByteArray, off: Int, len: Int): Int = read()
    override fun close() = closed.countDown()
}

internal class FakeRemote : IPorterRemoteProcess.Stub() {
    @Volatile var destroyed = false
    override fun getOutputStream(): ParcelFileDescriptor? = null
    override fun getInputStream(): ParcelFileDescriptor? = null
    override fun getErrorStream(): ParcelFileDescriptor? = null
    override fun waitFor(): Int = 0
    override fun exitValue(): Int = 0
    override fun destroy() { destroyed = true }
    override fun alive(): Boolean = !destroyed
    override fun waitForTimeout(timeout: Long, unit: String?): Boolean = false
}

/** The manager's own logcat, which runs until destroyed. */
internal class FakeProcess : Process() {
    val input = BlockingInput()
    override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
    override fun getInputStream(): InputStream = input
    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    override fun waitFor(): Int = 0
    override fun exitValue(): Int = 0
    override fun destroy() = input.close()
}

/** Records every service operation with the binder it was handed. */
internal class FakeOperations : ServiceFollower.Operations {
    data class Call(val operation: String, val binder: IBinder)
    data class Lease(val binder: IBinder, val token: IBinder, val durationMs: Long)
    class Opened(val binder: IBinder, val pid: Int, val since: String, val input: BlockingInput, val remote: FakeRemote) {
        val stream = ServerDiagnostics.ServerStream(pid, input, ByteArrayInputStream(ByteArray(0)), Job().apply { complete() }, remote)
        val isClosed get() = input.isClosed && remote.destroyed
    }

    val calls = CopyOnWriteArrayList<Call>()
    val leases = CopyOnWriteArrayList<Lease>()
    val releases = CopyOnWriteArrayList<Lease>()
    val streams = CopyOnWriteArrayList<Opened>()
    val metadata = CopyOnWriteArrayList<File>()

    val pids = HashMap<IBinder, Int>()
    @Volatile var onReadInfo: (IBinder) -> Unit = {}
    @Volatile var grant: (IBinder, Long) -> Long? = { _, requested -> requested }
    @Volatile var open: (IBinder) -> Boolean = { true }
    @Volatile var beforeOpenReturns: () -> Unit = {}

    fun callsFor(binder: IBinder) = calls.filter { it.binder === binder }

    override fun readInfo(binder: IBinder): ServerDiagnostics.Info? {
        calls += Call("readInfo", binder)
        onReadInfo(binder)
        return synchronized(pids) { pids[binder] }?.let { ServerDiagnostics.Info(it, null) }
    }

    override fun requestLease(binder: IBinder, token: IBinder, durationMs: Long): Long? {
        calls += Call("requestLease", binder)
        val granted = grant(binder, durationMs)
        leases += Lease(binder, token, durationMs)
        return granted
    }

    override fun releaseLease(binder: IBinder, token: IBinder) {
        calls += Call("releaseLease", binder)
        releases += Lease(binder, token, 0)
    }

    override fun openStream(binder: IBinder, pid: Int, directory: File, since: String): ServerDiagnostics.ServerStream? {
        calls += Call("openStream", binder)
        if (!open(binder)) return null
        val opened = Opened(binder, pid, since, BlockingInput(), FakeRemote())
        streams += opened
        beforeOpenReturns()
        return opened.stream
    }

    override suspend fun captureMetadata(binder: IBinder, file: File) {
        calls += Call("captureMetadata", binder)
        metadata += file
        file.writeText("metadata\n")
    }
}

/** Polls [condition] until it holds, failing after [timeoutMs]. */
internal fun eventually(timeoutMs: Long = 5_000, message: () -> String = { "condition never held" }, condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    while (!condition()) {
        if (System.nanoTime() > deadline) throw AssertionError(message())
        Thread.sleep(10)
    }
}
