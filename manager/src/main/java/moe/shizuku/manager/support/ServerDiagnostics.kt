package moe.shizuku.manager.support

import android.os.Parcel
import android.os.ParcelFileDescriptor
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import rikka.shizuku.server.ServerConstants
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal object ServerDiagnostics {
    private val timeouts = Executors.newSingleThreadScheduledExecutor { Thread(it, "porter-log-timeout").apply { isDaemon = true } }
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
            val request = Parcel.obtain()
            val reply = Parcel.obtain()
            val pid = try {
                request.writeInterfaceToken("moe.shizuku.server.IShizukuService")
                check(binder.transact(ServerConstants.BINDER_TRANSACTION_getDiagnostics, request, reply, 0))
                reply.readException()
                reply.readInt().also { check(it > 0) }
            } finally { request.recycle(); reply.recycle() }
            details.appendText("PID: $pid\n")
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
