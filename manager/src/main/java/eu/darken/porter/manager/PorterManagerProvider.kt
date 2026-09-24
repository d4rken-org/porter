package eu.darken.porter.manager

import android.os.Bundle
import eu.darken.porter.protocol.PorterProtocol
import eu.darken.porter.sdk.PorterApiProvider
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import eu.darken.porter.manager.utils.LOGGER
import eu.darken.porter.manager.utils.PorterStateMachine
import eu.darken.porter.privileged.ktx.workerHandler

class PorterManagerProvider : PorterApiProvider() {

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (extras == null) return null

        // Held before the SDK decides whether it can speak to the server: the manager's own
        // operations must reach a server the SDK refuses as well, and the user service starter
        // asks this provider for the binder its server launched it from.
        if (method == PorterProtocol.DELIVERY_METHOD_SEND_BINDER) {
            extras.getBinder(PorterProtocol.DELIVERY_EXTRA_BINDER)?.let { ServerBinder.deliver(it) }
        }
        if (method == PorterProtocol.DELIVERY_METHOD_GET_BINDER) {
            val binder = ServerBinder.binder.value?.takeIf { it.pingBinder() } ?: return super.call(method, arg, extras)
            return Bundle().apply { putBinder(PorterProtocol.DELIVERY_EXTRA_BINDER, binder) }
        }

        return if (method == PorterProtocol.DELIVERY_METHOD_SEND_USER_SERVICE) {
            try {
                val token = extras.getString(PorterProtocol.USER_SERVICE_TOKEN) ?: return null
                val binder = extras.getBinder(PorterProtocol.DELIVERY_EXTRA_BINDER) ?: return null

                return runBlocking {
                    try {
                        withTimeout(5000) {
                            PorterStateMachine.instance.asFlow().first { it == PorterStateMachine.State.RUNNING }
                            withContext(workerHandler.asCoroutineDispatcher()) {
                                try {
                                    val reply = Bundle()
                                    ServerBinder.manager().attachUserService(binder, token)
                                    reply.putBinder(PorterProtocol.DELIVERY_EXTRA_BINDER, ServerBinder.require())
                                    reply
                                } catch (e: Throwable) {
                                    LOGGER.e(e, "attachUserService")
                                    null
                                }
                            }
                        }
                    } catch (e: TimeoutCancellationException) {
                        LOGGER.e(e, "Binder not received in 5s")
                        null
                    }
                }
            } catch (e: Throwable) {
                LOGGER.e(e, "sendUserService")
                null
            }
        } else {
            super.call(method, arg, extras)
        }
    }
}
