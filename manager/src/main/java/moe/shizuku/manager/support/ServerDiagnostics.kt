package moe.shizuku.manager.support

import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import eu.darken.porter.common.PorterBuildIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.model.PorterServiceVersion
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import rikka.shizuku.server.ServerConstants
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

internal object ServerDiagnostics {
    private val drains = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    data class Info(val pid: Int, val version: PorterServiceVersion?)

    fun readInfo(binder: IBinder): Info? {
        val request = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            request.writeInterfaceToken("moe.shizuku.server.IShizukuService")
            if (!binder.transact(ServerConstants.BINDER_TRANSACTION_getDiagnostics, request, reply, 0)) return null
            reply.readException()
            val pid = reply.readInt().also { check(it > 0) }
            // Older Porter services return only the PID.
            val extra = if (reply.dataAvail() > 0) reply.readBundle() else null
            val name = extra?.getString(ServerConstants.DIAGNOSTICS_VERSION_NAME)
            val code = extra?.getInt(ServerConstants.DIAGNOSTICS_VERSION_CODE, -1) ?: -1
            val buildId = extra?.getString(PorterBuildIdentity.DIAGNOSTICS_KEY)?.takeIf { it.isNotBlank() }
            val version = if (!name.isNullOrBlank() && code >= 0) PorterServiceVersion(name, code, buildId) else null
            return Info(pid, version)
        } finally {
            request.recycle()
            reply.recycle()
        }
    }

    fun captureMetadata(directory: File, phase: String) {
        val details = File(directory, "server-$phase.txt")
        try {
            details.writeText("Time: ${System.currentTimeMillis()}\nBoot start: ${ShizukuSettings.getPreferences().getBoolean("start_on_boot", false)}\nWatchdog: ${ShizukuSettings.getWatchdog()}\n")
            val binder = Shizuku.getBinder()
            if (binder == null || !binder.pingBinder()) {
                details.appendText("Porter service unavailable\n")
                return
            }
            details.appendText("UID: ${Shizuku.getUid()}\nAPI: ${Shizuku.getVersion()}\nSELinux: ${Shizuku.getSELinuxContext()}\n")
            val info = readInfo(binder) ?: error("Service diagnostics unsupported")
            details.appendText("PID: ${info.pid}\nPorter service: ${info.version?.name ?: "unknown"} (${info.version?.code ?: "unknown"})\nInstalled build: ${PorterServiceVersion.installed.buildId}\nService build: ${info.version?.buildId ?: "unknown"}\nAPI patch: ${Shizuku.getServerPatchVersion()}\n")
        } catch (e: Exception) {
            runCatching { details.appendText("Server diagnostics unavailable: ${e.javaClass.simpleName}: ${e.message}\n") }
        }
    }

    /** Everything [openStream] acquired, released together by [close]. */
    open class ServerStream(
        val pid: Int,
        val input: InputStream,
        private val error: InputStream,
        private val errorDrain: Job,
        private val remote: IRemoteProcess,
    ) {
        private val closed = AtomicBoolean(false)

        open suspend fun close() = withContext(NonCancellable) {
            if (!closed.compareAndSet(false, true)) return@withContext
            // Destroying first gives the drain its EOF, so a logcat that refused its options still
            // reports why before the drain is cut short.
            runCatching { remote.destroy() }
            if (withTimeoutOrNull(4_000) { errorDrain.join() } == null) {
                errorDrain.cancel()
                runCatching { error.close() }
                // Cancellation is asynchronous; a buffered write must not land after close() returns.
                errorDrain.join()
            }
            runCatching { input.close() }
            runCatching { error.close() }
        }
    }

    fun openStream(directory: File): ServerStream? {
        val notes = File(directory, "server-stream.txt")
        var remote: IRemoteProcess? = null
        var input: InputStream? = null
        var error: InputStream? = null
        var drain: Job? = null
        try {
            val binder = Shizuku.getBinder()?.takeIf { it.pingBinder() } ?: return null
            val pid = readInfo(binder)?.pid ?: return null
            val process = IShizukuService.Stub.asInterface(binder)
                .newProcess(arrayOf("sh", "-c", supervisor(pid)), null, null).also { remote = it }
            val output = ParcelFileDescriptor.AutoCloseInputStream(process.inputStream).also { input = it }
            val errors = ParcelFileDescriptor.AutoCloseInputStream(process.errorStream).also { error = it }
            val started = drains.launch { drainServerErrors(errors, notes, STREAM_NOTES_BYTES) }.also { drain = it }
            return ServerStream(pid, output, errors, started, process)
        } catch (e: Exception) {
            // Anything acquired before the failure would otherwise keep running unreachable.
            drain?.cancel()
            runCatching { remote?.destroy() }
            runCatching { input?.close() }
            runCatching { error?.close() }
            runCatching { notes.appendText("Server stream unavailable: ${e.javaClass.simpleName}: ${e.message}\n") }
            return null
        }
    }

    // The remote logcat is a child of the server, whose death recipient runs inside the server, so
    // nothing destroys it once the server is gone. This supervisor is that missing reaper: the trap
    // precedes the spawn (with `pending` for a TERM in between) so an immediate teardown cannot
    // orphan the child, and the poll is 2s because a trap does not interrupt sleep.
    private fun supervisor(pid: Int) = listOf(
        "pending=0",
        "c=",
        "trap 'if [ -n \"\$c\" ]; then kill \$c 2>/dev/null; exit 0; else pending=1; fi' TERM INT",
        // -T 1 follows without replaying the retained backlog, which -t would charge against the cap.
        "logcat -v threadtime --pid=$pid -T 1 &",
        "c=\$!",
        "[ \"\$pending\" = 1 ] && { kill \$c 2>/dev/null; exit 0; }",
        "while kill -0 \$c 2>/dev/null && kill -0 $pid 2>/dev/null; do sleep 2; done",
        "kill \$c 2>/dev/null",
    ).joinToString("\n")

    private const val STREAM_NOTES_BYTES = 64L * 1024
}
