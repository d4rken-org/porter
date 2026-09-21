package eu.darken.porter.manager

import android.os.Bundle
import eu.darken.porter.protocol.PorterProtocol
import eu.darken.porter.sdk.Porter
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
                                    val connection = Porter.connection.value ?: error("Porter is not running")
                                    connection.attachUserService(binder, token)
                                    reply.putBinder(PorterProtocol.DELIVERY_EXTRA_BINDER, connection.binder)
                                    reply
                                } catch (e: Throwable) {
                                    LOGGER.e(e, "attachUserService $token")
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
