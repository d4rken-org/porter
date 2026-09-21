package eu.darken.porter.common

/** Manager-only global access control; independent of the public Shizuku API. */
object GlobalAccess {
    const val TRANSACTION = 10004
    const val VERSION = 1
    const val READ = 0
    const val WRITE = 1
}
