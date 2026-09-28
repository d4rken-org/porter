package eu.darken.porter.manager.updater

/**
 * A version as the release tooling writes it, `0.7.0-rc0` or `v0.7.0-beta2`. Ordered as the
 * versionCode orders builds, which leaves the suffix type out; beta sorts first only on a tie.
 */
data class ReleaseVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val type: Type,
    val build: Int,
) : Comparable<ReleaseVersion> {

    enum class Type { BETA, RC }

    override fun compareTo(other: ReleaseVersion): Int = compareValuesBy(
        this, other, { it.major }, { it.minor }, { it.patch }, { it.build }, { it.type },
    )

    companion object {
        private val PATTERN = Regex("""v?(\d+)\.(\d+)\.(\d+)-(rc|beta)(\d+)""")

        fun parse(text: String): ReleaseVersion? {
            val groups = PATTERN.matchEntire(text)?.groupValues ?: return null
            return ReleaseVersion(
                major = groups[1].toIntOrNull() ?: return null,
                minor = groups[2].toIntOrNull() ?: return null,
                patch = groups[3].toIntOrNull() ?: return null,
                type = if (groups[4] == "rc") Type.RC else Type.BETA,
                build = groups[5].toIntOrNull() ?: return null,
            )
        }

        /** False whenever either side does not parse. */
        fun isNewer(candidate: String, installed: String): Boolean {
            val new = parse(candidate) ?: return false
            val current = parse(installed) ?: return false
            return new > current
        }
    }
}
