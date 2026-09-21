package eu.darken.porter.manager

import eu.darken.porter.manager.utils.MultiLocaleEntity

object Helps {

    const val WEBSITE = "https://porter.darken.eu/"
    const val SOURCE = "https://github.com/d4rken-org/porter"
    const val STOPPING = WEBSITE + "troubleshooting#porter-keeps-stopping"
    const val STARTUP = WEBSITE + "troubleshooting#automatic-start-does-not-work"

    val ADB = MultiLocaleEntity().apply { put("en", WEBSITE + "setup#with-a-computer") }
    val ADB_ANDROID11 = MultiLocaleEntity().apply { put("en", WEBSITE + "setup#wireless-debugging") }
    val APPS = MultiLocaleEntity().apply { put("en", WEBSITE + "compatibility") }
    val HOME = MultiLocaleEntity().apply { put("en", WEBSITE + "developers") }
    val DOWNLOAD = MultiLocaleEntity().apply { put("en", SOURCE + "/releases") }
    val SUI = MultiLocaleEntity().apply { put("en", "https://github.com/RikkaApps/Sui") }
    val ADB_PERMISSION = MultiLocaleEntity().apply {
        put("en", WEBSITE + "troubleshooting#access-is-allowed-but-an-operation-still-fails")
    }
}
