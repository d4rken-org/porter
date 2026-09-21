package eu.darken.porter.common

/**
 * Porter-only launch validation for user services; independent of the Shizuku API.
 *
 * Lives here rather than in `:server` because the starter asks the question and `:server`
 * already depends on `:starter`.
 */
object UserServiceLaunch {
    const val TRANSACTION = 10007
}
