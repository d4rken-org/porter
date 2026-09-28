package eu.darken.porter.manager.updater

import android.content.Context
import android.content.pm.PackageInfo
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.URL
import java.net.URLConnection
import kotlinx.coroutines.CoroutineScope

/** Seams of [UpdateInstaller] recording what the installer asked for. */
internal class UpdateInstallerFixture(private val context: Context) {
    var body: ByteArray = ByteArray(1000) { it.toByte() }
    var opened = 0
    var consumed = 0L
    var beforeOpen: () -> Unit = {}
    var archive: (File) -> PackageInfo? = { packageInfo(context.packageName, INSTALLED + 1) }
    var installed = INSTALLED
    var available = true
    val commands = mutableListOf<List<String>>()
    val commandFiles = mutableListOf<Pair<String, Long>>()
    var commandResult: () -> String = { "Success" }
    val deleted = mutableListOf<Uri>()
    var deleteResult = true

    val updates get() = File(context.cacheDir, UpdateInstaller.UPDATES_DIRECTORY)

    fun asset(size: Long = body.size.toLong(), fileName: String = "porter-0.7.0.apk", url: String = "https://example.invalid/porter.apk") =
        Asset(fileName, url, size)

    private fun counting(input: InputStream) = object : InputStream() {
        override fun read(): Int = input.read().also { if (it >= 0) consumed++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = input.read(b, off, len).also { if (it > 0) consumed += it }
    }

    fun connection(url: String): URLConnection = object : URLConnection(URL(url)) {
        override fun connect() = Unit
        override fun getInputStream(): InputStream = counting(ByteArrayInputStream(body))
    }

    fun installer(scope: CoroutineScope, openUrl: ((String) -> URLConnection)? = null) = UpdateInstaller(
        context = context,
        scope = scope,
        openUrl = { url ->
            opened++
            beforeOpen()
            openUrl?.invoke(url) ?: connection(url)
        },
        archiveInfo = { archive(it) },
        installedVersion = { installed },
        runCommand = { arguments, apk ->
            commands += arguments.toList()
            commandFiles += apk.path to apk.length()
            commandResult()
        },
        automaticAvailable = { available },
        deleteDocument = { uri ->
            deleted += uri
            deleteResult
        },
    )

    companion object {
        const val INSTALLED = 10L

        fun packageInfo(name: String, version: Long) = PackageInfo().apply {
            packageName = name
            longVersionCode = version
        }
    }
}
