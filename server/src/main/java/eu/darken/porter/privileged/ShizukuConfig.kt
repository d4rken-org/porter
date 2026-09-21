package eu.darken.porter.privileged

import com.google.gson.annotations.SerializedName
import rikka.shizuku.server.ConfigManager
import rikka.shizuku.server.ConfigPackageEntry

class ShizukuConfig {

    @SerializedName("version")
    var version: Int = LATEST_VERSION

    @SerializedName("accessPaused")
    var accessPaused: Boolean = false

    /** Nullable because a stored file can lack it; the manager repairs it at load. */
    @SerializedName("packages")
    var packages: MutableList<PackageEntry>? = ArrayList()

    class PackageEntry(
        @SerializedName("uid")
        val uid: Int,
        @SerializedName("flags")
        var flags: Int,
    ) : ConfigPackageEntry() {

        /** Nullable because a stored entry can lack it; the manager repairs it at load. */
        @SerializedName("packages")
        var packages: MutableList<String>? = ArrayList()

        override fun isAllowed(): Boolean = (flags and ConfigManager.FLAG_ALLOWED) != 0

        fun isPendingCompanion(): Boolean = (flags and FLAG_PENDING_COMPANION) != 0

        override fun isDenied(): Boolean = (flags and ConfigManager.FLAG_DENIED) != 0
    }

    constructor()

    constructor(packages: MutableList<PackageEntry>) {
        this.version = LATEST_VERSION
        this.packages = packages
    }

    companion object {
        const val LATEST_VERSION = 2
        const val FLAG_PENDING_COMPANION = 1 shl 3
    }
}
