package eu.darken.porter.privileged

import eu.darken.porter.protocol.PorterProtocol

internal object ClientRouting {

    /** The binder wire a client is served over. */
    enum class Wire { PORTER, SHIZUKU }

    private const val SHIZUKU_PROVIDER_AUTHORITY_SUFFIX = ".shizuku"

    fun requests(permissions: Array<String>?, permission: String): Boolean =
        permissions != null && permission in permissions

    /** The wire this client is entitled to, or null for no delivery. */
    fun route(permissions: Array<String>?, trustedCompanion: Boolean): Wire? {
        if (requests(permissions, ServerConstants.PERMISSION)) return Wire.PORTER
        if (trustedCompanion && requests(permissions, ServerConstants.LEGACY_PERMISSION)) return Wire.SHIZUKU
        return null
    }

    /** The authority suffix the provider receiving the binder is registered under. */
    fun providerSuffix(wire: Wire): String =
        if (wire == Wire.PORTER) PorterProtocol.PROVIDER_AUTHORITY_SUFFIX else SHIZUKU_PROVIDER_AUTHORITY_SUFFIX
}
