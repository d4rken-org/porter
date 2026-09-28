package eu.darken.porter.manager.updater

import kotlinx.serialization.Serializable

interface UpdateChecker {
    val isSupported: Boolean

    fun isEnabledByDefault(): Boolean

    /** The newest release on the channel, or null when there is none. Throws when the check fails. */
    suspend fun latest(includePrereleases: Boolean): Release?
}

@Serializable
data class Release(
    val tag: String,
    val name: String?,
    val changelogUrl: String,
    /** Null when the release carries no manager APK. */
    val apk: Asset?,
)

@Serializable
data class Asset(
    val fileName: String,
    val url: String,
    val size: Long,
)
