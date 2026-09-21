package eu.darken.porter.common

import eu.darken.porter.protocol.PorterProtocol

/**
 * Every transaction code the Porter app allocates on the server binder, all at or above
 * [PorterProtocol.TRANSACTION_APP_BASE], where the protocol promises never to allocate one. A
 * removed code stays unallocated.
 */
object AppTransactions {
    const val GET_APPLICATIONS = PorterProtocol.TRANSACTION_APP_BASE + 1
    const val GET_DIAGNOSTICS = PorterProtocol.TRANSACTION_APP_BASE + 2
    const val DISCOVER_APPLICATIONS = PorterProtocol.TRANSACTION_APP_BASE + 3
    const val GLOBAL_ACCESS = PorterProtocol.TRANSACTION_APP_BASE + 4
    const val COMPATIBILITY_SETUP = PorterProtocol.TRANSACTION_APP_BASE + 5
    const val SET_DEBUG_LOGGING = PorterProtocol.TRANSACTION_APP_BASE + 6
    const val USER_SERVICE_LAUNCH = PorterProtocol.TRANSACTION_APP_BASE + 7

    /**
     * Answers the manager, and only the manager, with its `IPorterManager` binder: no arguments,
     * one strong binder in the reply.
     */
    const val GET_MANAGER = PorterProtocol.TRANSACTION_APP_BASE + 8
}
