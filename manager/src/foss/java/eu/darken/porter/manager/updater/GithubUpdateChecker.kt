package eu.darken.porter.manager.updater

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.VisibleForTesting
import eu.darken.porter.manager.BuildConfig
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class GithubUpdateChecker @VisibleForTesting internal constructor(
    private val installer: () -> String?,
    private val debugOverride: () -> String?,
    private val fetch: (String) -> String,
) : UpdateChecker {

    constructor(context: Context) : this(
        installer = { installerOf(context) },
        debugOverride = { UpdaterDebugOverride.releaseJson(context) },
        fetch = { url -> httpGet(url) },
    )

    class HttpStatusException(val code: Int, url: String) : IOException("HTTP $code for $url")

    override val isSupported: Boolean = true

    /** Stores that deliver updates themselves get no second prompt. */
    override fun isEnabledByDefault(): Boolean {
        val store = installer() ?: return true
        return !store.startsWith("org.fdroid.fdroid") && store !in UPDATING_STORES
    }

    /** With prereleases, the highest version among the 20 most recently created releases. */
    override suspend fun latest(includePrereleases: Boolean): Release? = withContext(Dispatchers.IO) {
        debugOverride()?.let { return@withContext json.decodeFromString(GithubRelease.serializer(), it).toRelease() }
        if (!includePrereleases) {
            val text = try {
                fetch(LATEST_URL)
            } catch (e: HttpStatusException) {
                if (e.code == 404) return@withContext null
                throw e
            }
            return@withContext json.decodeFromString(GithubRelease.serializer(), text).toRelease()
        }
        json.decodeFromString(ListSerializer(GithubRelease.serializer()), fetch(LIST_URL))
            .filter { !it.draft }
            .mapNotNull { release -> ReleaseVersion.parse(release.tag)?.let { it to release } }
            .maxByOrNull { it.first }
            ?.second
            ?.toRelease()
    }

    @Serializable
    private data class GithubRelease(
        @SerialName("tag_name") val tag: String,
        val name: String? = null,
        val body: String? = null,
        @SerialName("html_url") val htmlUrl: String,
        val draft: Boolean = false,
        val assets: List<GithubAsset> = emptyList(),
    ) {
        fun toRelease(): Release {
            val apkName = "porter-$tag-release.apk"
            return Release(
                tag = tag,
                name = name,
                changelogUrl = htmlUrl,
                apk = assets.firstOrNull { it.name == apkName }?.let { Asset(it.name, it.downloadUrl, it.size) },
            )
        }
    }

    @Serializable
    private data class GithubAsset(
        val name: String,
        @SerialName("browser_download_url") val downloadUrl: String,
        val size: Long,
    )

    companion object {
        private const val LATEST_URL = "https://api.github.com/repos/d4rken-org/porter/releases/latest"
        private const val LIST_URL = "https://api.github.com/repos/d4rken-org/porter/releases?per_page=20"
        private const val TIMEOUT_MS = 20_000

        private val UPDATING_STORES = setOf(
            "com.machiav3lli.fdroid",
            "com.looker.droidify",
            "dev.imranr.obtainium",
            "com.aurora.store",
            "in.sunilpaulmathew.izzyondroid",
            "eu.bubu1.fdroidclassic",
            "org.gdroid.gdroid",
        )

        private val json = Json { ignoreUnknownKeys = true }

        private fun installerOf(context: Context): String? = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(context.packageName)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }

        private fun httpGet(url: String): String {
            val connection = URL(url).openConnection() as HttpsURLConnection
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "Porter/${BuildConfig.VERSION_NAME}")
                val code = connection.responseCode
                if (code !in 200..299) throw HttpStatusException(code, url)
                return connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }
    }
}
