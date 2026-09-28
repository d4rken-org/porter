package eu.darken.porter.manager

internal fun isBetaVersion(versionName: String): Boolean = versionName.contains("-beta")
