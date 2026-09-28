package eu.darken.porter.manager.updater

import android.content.Context
import android.content.pm.PackageInfo
import android.net.Uri
import android.provider.DocumentsContract
import androidx.annotation.VisibleForTesting
import eu.darken.porter.manager.R
import eu.darken.porter.manager.compatibility.CompatibilityRepository
import eu.darken.porter.manager.compatibility.CompatibilityService
import eu.darken.porter.manager.service.ServiceSnapshot
import eu.darken.porter.manager.service.ServiceStatusRepository
import eu.darken.porter.manager.utils.LOGGER
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Whether Porter's own service can install an update for this user right now. */
internal val ServiceSnapshot.canInstallUpdate: Boolean get() = running && primaryUser && !busy

/** Downloads, saves and installs an offered release; one operation at a time, none of them cancellable. */
class UpdateInstaller @VisibleForTesting internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val openUrl: (String) -> URLConnection = { URL(it).openConnection() },
    private val archiveInfo: (File) -> PackageInfo? = { context.packageManager.getPackageArchiveInfo(it.path, 0) },
    private val installedVersion: () -> Long = {
        CompatibilityRepository.version(context.packageManager.getPackageInfo(context.packageName, 0))
    },
    private val runCommand: suspend (Array<String>, File) -> String = { arguments, apk ->
        CompatibilityService.command(arguments, apk)
    },
    private val automaticAvailable: () -> Boolean = {
        ServiceStatusRepository.get(context).state.value.canInstallUpdate
    },
    private val deleteDocument: (Uri) -> Boolean = { DocumentsContract.deleteDocument(context.contentResolver, it) },
) {

    private constructor(context: Context) : this(context, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    sealed interface Operation {
        enum class Kind { DOWNLOAD, INSTALL }

        data object Idle : Operation
        data class Working(val kind: Kind) : Operation
        data class Saved(val fileName: String) : Operation
        data class Failed(val message: String) : Operation
    }

    /** Carries a message that is ready to show. */
    private class Failure(message: String) : Exception(message)

    private val lock = Any()
    private val mutable = MutableStateFlow<Operation>(Operation.Idle)
    val operation: StateFlow<Operation> = mutable.asStateFlow()

    private val pendingHandoff = MutableStateFlow<File?>(null)

    /** A staged and checked APK waiting for Android's installer; take it with [takeHandoff]. */
    val handoff: StateFlow<File?> = pendingHandoff.asStateFlow()

    /** Guarded by [lock]; true from staging for a manual install until Android's installer returns. */
    private var manualActive = false

    private val directory get() = File(context.cacheDir, UPDATES_DIRECTORY)

    /** Returns null when another operation is still running. [work] returns null to stay in its current state. */
    private fun start(work: suspend () -> Operation?): Job? {
        synchronized(lock) {
            if (mutable.value is Operation.Working) {
                LOGGER.i("Update operation ignored: %s still running", mutable.value)
                return null
            }
            mutable.value = Operation.Working(Operation.Kind.DOWNLOAD)
        }
        return scope.launch {
            val result = try {
                work()
            } catch (e: CancellationException) {
                publish(Operation.Idle)
                throw e
            } catch (e: Failure) {
                Operation.Failed(e.message.orEmpty())
            } catch (e: Exception) {
                LOGGER.w(e, "Update operation")
                Operation.Failed(detail(e))
            }
            if (result != null) publish(result)
        }
    }

    private fun publish(operation: Operation) {
        synchronized(lock) { mutable.value = operation }
    }

    /** Downloads the APK into the document the user created at [target]. */
    fun save(asset: Asset, target: Uri): Job? = start {
        try {
            fetch(asset) { context.contentResolver.openOutputStream(target) ?: throw IOException("The file could not be opened") }
            Operation.Saved(asset.fileName)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            LOGGER.w(e, "Save the Porter update")
            val removed = try {
                deleteDocument(target)
            } catch (d: Exception) {
                LOGGER.w(d, "Delete the partial Porter update")
                false
            }
            Operation.Failed(context.getString(
                if (removed) R.string.updater_download_failed else R.string.updater_download_failed_partial, detail(e)))
        }
    }

    /** Stages and checks the APK, then offers it through [handoff]; [finishManualInstall] ends the operation. */
    fun installManually(asset: Asset): Job? = start {
        val file = prepare(asset)
        synchronized(lock) {
            manualActive = true
            pendingHandoff.value = file
            mutable.value = Operation.Working(Operation.Kind.INSTALL)
        }
        null
    }

    /** On success Android replaces Porter and ends this process, so the operation may never complete here. */
    fun installAutomatically(asset: Asset): Job? = start {
        val file = prepare(asset)
        publish(Operation.Working(Operation.Kind.INSTALL))
        try {
            if (!automaticAvailable()) throw Failure(context.getString(R.string.updater_automatic_unavailable))
            try {
                runCommand(arrayOf("pm", "install", "-r", "--user", "0", "-S", file.length().toString()), file)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                LOGGER.w(e, "Install the Porter update")
                throw Failure(context.getString(R.string.updater_install_failed, detail(e)))
            }
            Operation.Idle
        } finally {
            clearStaged()
        }
    }

    /** Returns the staged APK once, or null when none is waiting. */
    fun takeHandoff(): File? = synchronized(lock) { pendingHandoff.value.also { pendingHandoff.value = null } }

    /** Android's installer returned without replacing Porter, or could not be opened ([failure]). */
    fun finishManualInstall(failure: String? = null) {
        synchronized(lock) {
            if (!manualActive) return
            manualActive = false
            pendingHandoff.value = null
            mutable.value = failure?.let { Operation.Failed(it) } ?: Operation.Idle
            clearStaged()
        }
    }

    /** A failure found before an operation could start, such as a refused install permission. */
    fun reportFailure(message: String) {
        synchronized(lock) { if (mutable.value !is Operation.Working) mutable.value = Operation.Failed(message) }
    }

    private fun prepare(asset: Asset): File {
        val file = try {
            stage(asset)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            LOGGER.w(e, "Download the Porter update")
            throw Failure(context.getString(R.string.updater_download_failed, detail(e)))
        }
        try {
            verify(file)
        } catch (e: Exception) {
            clearStaged()
            throw e
        }
        return file
    }

    private fun stage(asset: Asset): File {
        val name = asset.fileName
        if (name.isBlank() || name != File(name).name || name == "." || name == "..") {
            throw IOException("Unusable file name: $name")
        }
        clearStaged()
        val directory = directory
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Could not create $directory")
        val part = File(directory, "$name.part")
        val file = File(directory, name)
        try {
            fetch(asset) { FileOutputStream(part) }
            if (!part.renameTo(file)) throw IOException("Could not move the download into place")
        } catch (e: Exception) {
            part.delete()
            file.delete()
            throw e
        }
        return file
    }

    /** Signers are left to Android, which only replaces Porter with an APK of the same signer. */
    private fun verify(file: File) {
        val info = archiveInfo(file)
        if (info == null || info.packageName != context.packageName) throw Failure(context.getString(R.string.updater_not_porter))
        if (CompatibilityRepository.version(info) <= installedVersion()) throw Failure(context.getString(R.string.updater_not_newer))
    }

    private fun clearStaged() {
        directory.listFiles()?.forEach { if (!it.deleteRecursively()) LOGGER.w("Could not delete %s", it) }
    }

    /** Succeeds only when exactly [Asset.size] bytes arrived and the output closed cleanly. */
    private fun fetch(asset: Asset, open: () -> OutputStream) {
        if (asset.size <= 0) throw IOException("The release lists no APK size")
        val connection = openUrl(asset.url)
        try {
            if (connection is HttpURLConnection) {
                connection.connectTimeout = CONNECT_TIMEOUT
                connection.readTimeout = READ_TIMEOUT
                connection.instanceFollowRedirects = true
                val code = connection.responseCode
                if (code !in 200..299) throw IOException("HTTP $code")
            }
            val received = connection.getInputStream().use { input -> open().use { output -> copy(input, output, asset.size) } }
            if (received != asset.size) throw IOException("Received $received of ${asset.size} bytes")
        } finally {
            (connection as? HttpURLConnection)?.disconnect()
        }
    }

    private fun copy(input: InputStream, output: OutputStream, expected: Long): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var received = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return received
            received += count
            if (received > expected) throw IOException("Received more than the expected $expected bytes")
            output.write(buffer, 0, count)
        }
    }

    private fun detail(e: Exception): String = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName

    companion object {
        @VisibleForTesting internal const val BUFFER_SIZE = 8 * 1024
        @VisibleForTesting internal const val UPDATES_DIRECTORY = "updates"
        private const val CONNECT_TIMEOUT = 20_000
        private const val READ_TIMEOUT = 60_000

        @Volatile private var instance: UpdateInstaller? = null

        fun get(context: Context): UpdateInstaller = instance ?: synchronized(this) {
            instance ?: UpdateInstaller(context.applicationContext).also { instance = it }
        }
    }
}
