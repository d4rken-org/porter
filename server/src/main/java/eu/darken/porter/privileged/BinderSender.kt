package eu.darken.porter.privileged

import android.app.ActivityManagerHidden
import android.app.ActivityManagerHidden.UID_OBSERVER_ACTIVE
import android.app.ActivityManagerHidden.UID_OBSERVER_CACHED
import android.app.ActivityManagerHidden.UID_OBSERVER_GONE
import android.app.ActivityManagerHidden.UID_OBSERVER_IDLE
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.RemoteException
import android.text.TextUtils
import androidx.annotation.RequiresApi
import eu.darken.porter.privileged.util.Android17Compat
import rikka.hidden.compat.ActivityManagerApis
import rikka.hidden.compat.PackageManagerApis
import rikka.hidden.compat.adapter.ProcessObserverAdapter
import rikka.hidden.compat.adapter.UidObserverAdapter
import rikka.shizuku.server.util.Logger

object BinderSender {

    private val LOGGER = Logger("BinderSender")

    private const val PERMISSION_MANAGER = "eu.darken.porter.permission.MANAGER"
    private const val PERMISSION = "eu.darken.porter.permission.API_V23"

    private lateinit var shizukuBinder: Binder
    private lateinit var porterBinder: Binder

    internal fun resetDelivery() {
        synchronized(ProcessObserver.PID_LIST) { ProcessObserver.PID_LIST.clear() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            synchronized(UidObserver.UID_LIST) { UidObserver.UID_LIST.clear() }
        }
    }

    private class ProcessObserver : ProcessObserverAdapter() {

        @Throws(RemoteException::class)
        override fun onForegroundActivitiesChanged(pid: Int, uid: Int, foregroundActivities: Boolean) {
            LOGGER.d("onForegroundActivitiesChanged: pid=%d, uid=%d, foregroundActivities=%s", pid, uid, if (foregroundActivities) "true" else "false")

            synchronized(PID_LIST) {
                if (PID_LIST.contains(pid) || !foregroundActivities) {
                    return
                }
                PID_LIST.add(pid)
            }

            sendBinder(uid, pid)
        }

        override fun onProcessDied(pid: Int, uid: Int) {
            LOGGER.d("onProcessDied: pid=%d, uid=%d", pid, uid)

            synchronized(PID_LIST) {
                val index = PID_LIST.indexOf(pid)
                if (index != -1) {
                    PID_LIST.removeAt(index)
                }
            }
        }

        @Throws(RemoteException::class)
        override fun onProcessStateChanged(pid: Int, uid: Int, procState: Int) {
            LOGGER.d("onProcessStateChanged: pid=%d, uid=%d, procState=%d", pid, uid, procState)

            synchronized(PID_LIST) {
                if (PID_LIST.contains(pid)) {
                    return
                }
                PID_LIST.add(pid)
            }

            sendBinder(uid, pid)
        }

        companion object {
            val PID_LIST = ArrayList<Int>()
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    private class UidObserver : UidObserverAdapter() {

        @Throws(RemoteException::class)
        override fun onUidActive(uid: Int) {
            LOGGER.d("onUidCachedChanged: uid=%d", uid)

            uidStarts(uid)
        }

        @Throws(RemoteException::class)
        override fun onUidCachedChanged(uid: Int, cached: Boolean) {
            LOGGER.d("onUidCachedChanged: uid=%d, cached=%s", uid, cached.toString())

            if (!cached) {
                uidStarts(uid)
            }
        }

        @Throws(RemoteException::class)
        override fun onUidIdle(uid: Int, disabled: Boolean) {
            LOGGER.d("onUidIdle: uid=%d, disabled=%s", uid, disabled.toString())

            uidStarts(uid)
        }

        @Throws(RemoteException::class)
        override fun onUidGone(uid: Int, disabled: Boolean) {
            LOGGER.d("onUidGone: uid=%d, disabled=%s", uid, disabled.toString())

            uidGone(uid)
        }

        @Throws(RemoteException::class)
        private fun uidStarts(uid: Int) {
            synchronized(UID_LIST) {
                if (UID_LIST.contains(uid)) {
                    LOGGER.v("Uid %d already starts", uid)
                    return
                }
                UID_LIST.add(uid)
                LOGGER.v("Uid %d starts", uid)
            }

            sendBinder(uid, -1)
        }

        private fun uidGone(uid: Int) {
            synchronized(UID_LIST) {
                val index = UID_LIST.indexOf(uid)
                if (index != -1) {
                    UID_LIST.removeAt(index)
                    LOGGER.v("Uid %d dead", uid)
                }
            }
        }

        companion object {
            val UID_LIST = ArrayList<Int>()
        }
    }

    @Throws(RemoteException::class)
    private fun sendBinder(uid: Int, pid: Int) {
        val packages = PackageManagerApis.getPackagesForUidNoThrow(uid)
        if (packages.isEmpty()) return

        if (Logger.debugEnabled()) {
            LOGGER.d("sendBinder to uid %d: packages=%s", uid, TextUtils.join(", ", packages))
        }

        val userId = uid / 100000
        for (packageName in packages) {
            val pi = Android17Compat.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS.toLong(), userId)
            val requestedPermissions = pi?.requestedPermissions ?: continue

            if (PERMISSION_MANAGER in requestedPermissions) {
                val granted = if (pid == -1) {
                    Android17Compat.checkPermission(PERMISSION_MANAGER, uid) == PackageManager.PERMISSION_GRANTED
                } else {
                    ActivityManagerApis.checkPermission(PERMISSION_MANAGER, pid, uid) == PackageManager.PERMISSION_GRANTED
                }

                if (granted) {
                    PorterServer.sendBinderToManager(porterBinder, userId)
                    return
                }
            } else {
                val wire = PorterServer.route(pi)
                if (wire != null) {
                    PorterServer.sendBinderToUserApp(
                        wire,
                        if (wire == ClientRouting.Wire.PORTER) porterBinder else shizukuBinder, packageName, userId,
                    )
                    return
                }
            }
        }
    }

    fun register(shizukuBinder: Binder, porterBinder: Binder) {
        this.shizukuBinder = shizukuBinder
        this.porterBinder = porterBinder

        try {
            ActivityManagerApis.registerProcessObserver(ProcessObserver())
        } catch (tr: Throwable) {
            LOGGER.e(tr, "registerProcessObserver")
        }

        if (Build.VERSION.SDK_INT >= 26) {
            var flags = UID_OBSERVER_GONE or UID_OBSERVER_IDLE or UID_OBSERVER_ACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                flags = flags or UID_OBSERVER_CACHED
            }
            try {
                ActivityManagerApis.registerUidObserver(
                    UidObserver(), flags,
                    ActivityManagerHidden.PROCESS_STATE_UNKNOWN,
                    null,
                )
            } catch (tr: Throwable) {
                LOGGER.e(tr, "registerUidObserver")
            }
        }
    }
}
