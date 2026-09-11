package moe.shizuku.manager.support

import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import eu.darken.porter.common.PorterBuildIdentity
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.model.PorterServiceVersion
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import rikka.shizuku.server.ServerConstants
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal object ServerDiagnostics {
    private val timeouts = Executors.newSingleThreadScheduledExecutor { Thread(it, "porter-log-timeout").apply { isDaemon = true } }
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

    fun capture(directory: File, phase: String) {
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
            val pid = info.pid
            details.appendText("PID: $pid\nPorter service: ${info.version?.name ?: "unknown"} (${info.version?.code ?: "unknown"})\nInstalled build: ${PorterServiceVersion.installed.buildId}\nService build: ${info.version?.buildId ?: "unknown"}\nAPI patch: ${Shizuku.getServerPatchVersion()}\n")
            val remote = IShizukuService.Stub.asInterface(binder).newProcess(
                arrayOf("logcat", "-d", "-v", "threadtime", "-t", "2000", "--pid=$pid"), null, null,
            )
            val pipe = remote.inputStream
            val timeout = timeouts.schedule({
                runCatching { pipe.close() }
                runCatching { remote.destroy() }
            }, 2, TimeUnit.SECONDS)
            try {
                ParcelFileDescriptor.AutoCloseInputStream(pipe).use {
                    DebugLogStore.appendBounded(it, File(directory, "server-$phase.log"), 1024 * 1024)
                }
            } finally {
                timeout.cancel(false)
                runCatching { remote.destroy() }
            }
        } catch (e: Exception) {
            runCatching { details.appendText("Server diagnostics unavailable: ${e.javaClass.simpleName}: ${e.message}\n") }
        }
    }
}
