package eu.darken.porter.manager.utils

import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import eu.darken.porter.sdk.Porter
import java.io.FileDescriptor

/**
 * Forwards every transaction on [original] through whichever Porter connection is current when the
 * transaction happens. The system service binders are wrapped once, at attach, before any
 * connection exists, so the connection is looked up per call rather than captured.
 */
class PorterForwardingBinder(private val original: IBinder) : IBinder {

    private fun forwarder(): IBinder =
        (Porter.connection.value ?: error("Porter is not running")).wrap(original)

    override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
        forwarder().transact(code, data, reply, flags)

    override fun getInterfaceDescriptor(): String? = original.interfaceDescriptor

    override fun pingBinder(): Boolean = original.pingBinder()

    override fun isBinderAlive(): Boolean = original.isBinderAlive

    override fun queryLocalInterface(descriptor: String): IInterface? = null

    override fun dump(fd: FileDescriptor, args: Array<String>?) = original.dump(fd, args)

    override fun dumpAsync(fd: FileDescriptor, args: Array<String>?) = original.dumpAsync(fd, args)

    override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) = original.linkToDeath(recipient, flags)

    override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean =
        original.unlinkToDeath(recipient, flags)
}
