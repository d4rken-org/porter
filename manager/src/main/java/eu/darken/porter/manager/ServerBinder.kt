package eu.darken.porter.manager

import android.os.IBinder
import android.os.Parcel
import eu.darken.porter.common.AppTransactions
import eu.darken.porter.protocol.PorterProtocol
import eu.darken.porter.server.IPorterManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The server binder as it was delivered to this process, whether or not the SDK went on to
 * publish a connection for it. The manager stops and replaces a server it cannot speak to as
 * well as one it can, so its own operations are reached from here, not from [eu.darken.porter.sdk.Porter.connection].
 */
object ServerBinder {

    private val lock = Any()
    private val _binder = MutableStateFlow<IBinder?>(null)

    /** The delivered binder while it lives; null before a delivery and after the server died. */
    val binder: StateFlow<IBinder?> = _binder.asStateFlow()

    /** The manager binder obtained for [held], so one raw transaction serves every call. */
    private var manager: IPorterManager? = null
    private var held: IBinder? = null

    private val deathRecipient = IBinder.DeathRecipient {
        synchronized(lock) {
            val dead = held?.takeIf { !it.pingBinder() } ?: return@DeathRecipient
            forget(dead)
        }
    }

    /** Takes [newBinder] as the server, replacing whatever was delivered before. */
    fun deliver(newBinder: IBinder) {
        synchronized(lock) {
            if (held === newBinder) return
            held?.let { previous ->
                runCatching { previous.unlinkToDeath(deathRecipient, 0) }
            }
            try {
                newBinder.linkToDeath(deathRecipient, 0)
            } catch (e: Exception) {
                // Already dead: nothing to hold.
                return
            }
            held = newBinder
            manager = null
            _binder.value = newBinder
        }
    }

    /** Drops [binder] if it is still the one held; a later delivery is left alone. */
    fun drop(binder: IBinder) {
        synchronized(lock) {
            if (held === binder) forget(binder)
        }
    }

    private fun forget(binder: IBinder) {
        runCatching { binder.unlinkToDeath(deathRecipient, 0) }
        held = null
        manager = null
        _binder.value = null
    }

    /** The server binder, or an error naming what is missing. */
    fun require(): IBinder = binder.value ?: error("Porter is not running")

    /** The manager's operations on the held server. */
    fun manager(): IPorterManager {
        val server = require()
        synchronized(lock) {
            if (held === server) manager?.let { return it }
        }
        val obtained = managerOf(server)
        synchronized(lock) {
            if (held === server) manager = obtained
        }
        return obtained
    }

    /** Asks [server] for its manager binder; refused for anyone but the manager. */
    fun managerOf(server: IBinder): IPorterManager {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(PorterProtocol.DESCRIPTOR)
            check(server.transact(AppTransactions.GET_MANAGER, data, reply, 0)) { "The Porter service does not hand out a manager binder" }
            reply.readException()
            val binder = reply.readStrongBinder() ?: error("The Porter service answered without a manager binder")
            return IPorterManager.Stub.asInterface(binder)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
