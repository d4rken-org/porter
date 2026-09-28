package eu.darken.porter.manager.updater

enum class UpdateChannel(val key: String) {
    PRODUCTION("production"),
    BETA("beta"),
    ;

    companion object {
        fun fromKey(key: String?): UpdateChannel? = entries.firstOrNull { it.key == key }
    }
}
