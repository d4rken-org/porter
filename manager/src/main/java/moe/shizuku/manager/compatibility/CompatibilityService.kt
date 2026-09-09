package moe.shizuku.manager.compatibility

import android.os.Bundle
import android.os.Parcel
import android.os.ParcelFileDescriptor
import eu.darken.porter.common.CompatibilitySetup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal object CompatibilityService {
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "porter-compat-timeout").apply { isDaemon = true } }

    fun request(operation: Int, snapshot: String? = null): Bundle {
        val binder = Shizuku.getBinder() ?: error("Porter is not running")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken("moe.shizuku.server.IShizukuService")
            data.writeInt(operation)
            data.writeString(snapshot)
            check(binder.transact(CompatibilitySetup.TRANSACTION, data, reply, 0)) { "Restart Porter to use compatibility setup" }
            reply.readException()
            val result = reply.readBundle() ?: error("Empty compatibility response")
            check(result.getInt("version") == CompatibilitySetup.VERSION) { "Restart Porter to use compatibility setup" }
            return result
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    suspend fun command(arguments: Array<String>, apk: File? = null): String = withContext(Dispatchers.IO) {
        val binder = Shizuku.getBinder() ?: error("Porter is not running")
        val process = IShizukuService.Stub.asInterface(binder).newProcess(arguments, null, null)
        val stdout = ParcelFileDescriptor.AutoCloseInputStream(process.inputStream)
        val stderr = ParcelFileDescriptor.AutoCloseInputStream(process.errorStream)
        val stdin = ParcelFileDescriptor.AutoCloseOutputStream(process.outputStream)
        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        val timeout = timer.schedule({
            timedOut.set(true)
            runCatching { process.destroy() }
            runCatching { stdin.close() }
            runCatching { stdout.close() }
            runCatching { stderr.close() }
        }, 90, TimeUnit.SECONDS)
        try {
            coroutineScope {
                val output = async(Dispatchers.IO) { stdout.use { readBounded(it) } }
                val errors = async(Dispatchers.IO) { stderr.use { readBounded(it) } }
                val writeFailure = runCatching { stdin.use { sink -> apk?.inputStream()?.use { it.copyTo(sink) } } }.exceptionOrNull()
                val code = process.waitFor()
                val text = (output.await() + "\n" + errors.await()).trim()
                check(!timedOut.get()) { "Package operation timed out" }
                check(code == 0) { text.ifBlank { "Package operation failed ($code)" } }
                if (writeFailure != null) throw writeFailure
                text
            }
        } finally {
            timeout.cancel(false)
            runCatching { process.destroy() }
            runCatching { stdin.close() }
            runCatching { stdout.close() }
            runCatching { stderr.close() }
        }
    }

    private fun readBounded(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            val remaining = 16 * 1024 - bytes.size()
            if (remaining > 0) bytes.write(buffer, 0, minOf(count, remaining))
        }
        return bytes.toString("UTF-8")
    }
}
