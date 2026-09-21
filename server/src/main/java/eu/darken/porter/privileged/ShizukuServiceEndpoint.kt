package eu.darken.porter.privileged

import android.os.Binder
import android.os.Parcel
import android.os.RemoteException
import rikka.shizuku.server.ShizukuLegacyEndpoint

/**
 * The Shizuku wire as Porter answers it. The manager never speaks it, so none of the app's own
 * transaction codes are answered here.
 */
open class ShizukuServiceEndpoint(private val service: PorterServer) : ShizukuLegacyEndpoint(service.core, service) {

    @Throws(RemoteException::class)
    open override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        // Attach builds and delivers its reply below this call; setGlobalAccess holds the same
        // monitor across its own bindApplication, so a pause can never overtake an attach reply.
        // Raw 14 is the pre-v13 attach the legacy endpoint handles itself; 18 is
        // IShizukuService.attachApplication, whose AIDL id is 17.
        if (code == 14 || code == Binder.FIRST_CALL_TRANSACTION + 17) {
            synchronized(service.core.clientManager) {
                return super.onTransact(code, data, reply, flags)
            }
        }
        return super.onTransact(code, data, reply, flags)
    }
}
