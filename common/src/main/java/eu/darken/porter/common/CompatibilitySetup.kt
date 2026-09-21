package eu.darken.porter.common

/** Porter manager-only setup operations; independent of the client SDK. */
object CompatibilitySetup {
    const val TRANSACTION = AppTransactions.COMPATIBILITY_SETUP
    const val VERSION = 1
    const val INSPECT = 0
    const val PREVIEW_IMPORT = 1
    const val APPLY_IMPORT = 2
    const val REFRESH = 3
    const val MAX_SNAPSHOT_BYTES = 256 * 1024
}
