package eu.darken.porter.manager.shell

import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Parcel
import eu.darken.porter.manager.utils.Logger.LOGGER
import eu.darken.porter.sdk.Porter

object ShellBinderRequestHandler {

    fun handleRequest(context: Context, intent: Intent): Boolean {
        if (intent.action != "eu.darken.porter.intent.action.REQUEST_BINDER") {
            return false
        }

        val binder = intent.getBundleExtra("data")?.getBinder("binder") ?: return false
        val serverBinder = Porter.getBinder()
        if (serverBinder == null) {
            LOGGER.w("Binder not received or Porter service not running")
        }

        val data = Parcel.obtain()
        return try {
            data.writeStrongBinder(serverBinder)
            data.writeString(context.applicationInfo.sourceDir)
            binder.transact(1, data, null, IBinder.FLAG_ONEWAY)
            true
        } catch (e: Throwable) {
            e.printStackTrace()
            false
        } finally {
            data.recycle()
        }
    }
}
