package eu.darken.porter.manager.updater

/** Google Play delivers its own updates. */
object UnsupportedUpdateChecker : UpdateChecker {
    override val isSupported: Boolean = false

    override fun isEnabledByDefault(): Boolean = false

    override suspend fun latest(includePrereleases: Boolean): Release? = null
}
