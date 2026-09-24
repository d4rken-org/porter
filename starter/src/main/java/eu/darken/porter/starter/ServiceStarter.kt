package eu.darken.porter.starter

import android.content.IContentProvider
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import eu.darken.porter.common.UserServiceLaunch
import eu.darken.porter.common.util.SignerDigests
import eu.darken.porter.protocol.PorterProtocol
import eu.darken.porter.starter.util.IContentProviderCompat
import java.util.Locale
import rikka.hidden.compat.ActivityManagerApis
import rikka.hidden.compat.PackageManagerApis
import rikka.shizuku.server.UserService

class ServiceStarter {

    /**
     * Where the launch-token check reads the manager's server binder from. A read yields the live
     * binder, or null while the manager has none to hand over.
     */
    // Public, like the Java package-private was to a test in this package name: the test that
    // drives the wait lives in the server module.
    interface BinderSource {
        @Throws(Throwable::class)
        fun read(): IBinder?
    }

    /** The waiting, injectable: measured in real time a never-warming manager costs the full 5s. */
    interface Pacer {
        fun now(): Long

        @Throws(InterruptedException::class)
        fun pause(millis: Long)
    }

    /** Holds one external provider reference per [read] until [close]. */
    private class ManagerBinderSource(private val name: String, private val userId: Int) : BinderSource {

        private var references = 0

        @Throws(Throwable::class)
        override fun read(): IBinder? {
            // On every attempt, because this call is what starts the manager's process: retrying
            // against a reference taken while it was dead would wait for a process nobody asked to
            // come up. Each one taken is given back in close().
            val provider = ActivityManagerApis.getContentProviderExternal(name, userId, null, name)
            if (provider == null) {
                Log.e(TAG, String.format("provider is null %s %d", name, userId))
                return null
            }
            references++
            // getBinder hands back the server binder without attaching. Both layers reject null extras.
            val reply = IContentProviderCompat.call(
                provider, null, null, name,
                PorterProtocol.DELIVERY_METHOD_GET_BINDER, null, Bundle(),
            ) ?: return null
            val serverBinder = reply.getBinder(PorterProtocol.DELIVERY_EXTRA_BINDER)
            if (serverBinder == null || !serverBinder.pingBinder()) {
                return null
            }
            return serverBinder
        }

        fun close() {
            while (references > 0) {
                references--
                try {
                    ActivityManagerApis.removeContentProviderExternal(name, null)
                } catch (tr: Throwable) {
                    Log.w(TAG, "removeContentProviderExternal", tr)
                }
            }
        }
    }

    companion object {

        private const val TAG = "PorterServiceStarter"

        /** What the attach in [sendBinder] waits for the manager's state machine, to the ms. */
        private const val LAUNCH_TOKEN_BINDER_TIMEOUT = 5000L
        private const val LAUNCH_TOKEN_BINDER_RETRY = 250L

        val DEBUG_ARGS: String = run {
            val sdk = Build.VERSION.SDK_INT
            if (sdk >= 30) {
                "-Xcompiler-option" + " --debuggable" +
                    " -XjdwpProvider:adbconnection" +
                    " -XjdwpOptions:suspend=n,server=y"
            } else if (sdk >= 28) {
                "-Xcompiler-option" + " --debuggable" +
                    " -XjdwpProvider:internal" +
                    " -XjdwpOptions:transport=dt_android_adb,suspend=n,server=y"
            } else {
                "-Xcompiler-option" + " --debuggable" +
                    " -agentlib:jdwp=transport=dt_android_adb,suspend=n,server=y"
            }
        }

        private const val USER_SERVICE_CMD_FORMAT = "(CLASSPATH=%s %s%s /system/bin " +
            "--nice-name=%s eu.darken.porter.starter.ServiceStarter " +
            "--manager=%s --token=%s --package=%s --class=%s --uid=%d --signers=%s%s)&"

        // DeathRecipient will automatically be unlinked when all references to the
        // binder is dropped, so we hold the reference here.
        @Suppress("unused")
        private var shizukuBinder: IBinder? = null

        /**
         * One shell word holding [value] verbatim: `it's` becomes `'it'\''s'`. The class name and the
         * process name suffix are the calling app's own strings.
         */
        fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

        fun commandForUserService(
            appProcess: String,
            managerApkPath: String,
            managerPackageName: String,
            token: String,
            packageName: String,
            classname: String,
            processNameSuffix: String?,
            callingUid: Int,
            signerDigests: Set<String>,
            debug: Boolean,
        ): String {
            val processName = String.format("%s:%s", packageName, processNameSuffix)
            return String.format(
                Locale.ENGLISH, USER_SERVICE_CMD_FORMAT,
                shellQuote(managerApkPath), shellQuote(appProcess), if (debug) " $DEBUG_ARGS" else "",
                shellQuote(processName),
                shellQuote(managerPackageName), shellQuote(token), shellQuote(packageName), shellQuote(classname), callingUid,
                shellQuote(signerDigests.joinToString(",")),
                if (debug) " " + shellQuote("--debug-name=$processName") else "",
            )
        }

        private var managerPackageName: String = BuildConfig.MANAGER_APPLICATION_ID

        @JvmStatic
        fun main(args: Array<String>) {
            if (Looper.getMainLooper() == null) {
                Looper.prepareMainLooper()
            }

            var launchToken: String? = null
            for (arg in args) {
                if (arg.startsWith("--manager=")) {
                    managerPackageName = arg.substring("--manager=".length)
                } else if (arg.startsWith("--token=")) {
                    launchToken = arg.substring("--token=".length)
                }
            }

            // Before UserService.create, because that is where the client's code is loaded into this
            // privileged process. It is the revocation case this exists for: setAccessPaused and a
            // permission revocation both drop records, and without this a launch already in flight runs
            // the app's code once regardless. It is not a time bound - the framework bootstrap inside
            // create() can stretch - and it is not what catches a signer substitution.
            if (!isLaunchTokenLive(launchToken)) {
                Log.w(TAG, "user service token is not live, exiting")
                System.exit(1)
                return
            }

            // create() resolves the package by name again. Between the bind and now, the approved
            // installation may have been replaced by another signer's under the same name.
            if (!isExpectedInstallation(args)) {
                Log.w(TAG, "installation is not the one the service was bound for, exiting")
                System.exit(1)
                return
            }

            UserService.setTag(TAG)
            val result = UserService.create(args)

            if (result == null) {
                System.exit(1)
                return
            }

            val service: IBinder = result.first
            val token: String? = result.second

            if (!sendBinder(service, token)) {
                System.exit(1)
            }

            Looper.loop()
            System.exit(0)

            Log.i(TAG, "service exited")
        }

        private fun isExpectedInstallation(args: Array<String>): Boolean {
            var packageName: String? = null
            var uid = -1
            var signers: Set<String>? = null
            for (arg in args) {
                when {
                    arg.startsWith("--package=") -> packageName = arg.substring("--package=".length)
                    arg.startsWith("--uid=") -> uid = arg.substring("--uid=".length).toIntOrNull() ?: -1
                    arg.startsWith("--signers=") ->
                        signers = arg.substring("--signers=".length).split(',').filter { it.isNotEmpty() }.toSet()
                }
            }
            if (packageName == null || uid < 0 || signers.isNullOrEmpty()) return false
            val info = PackageManagerApis.getPackageInfoNoThrow(packageName, SignerDigests.lookupFlags(), uid / 100000)
                ?: return false
            return info.applicationInfo?.uid == uid && SignerDigests.of(info) == signers
        }

        private val SYSTEM_PACER: Pacer = object : Pacer {
            override fun now(): Long = SystemClock.uptimeMillis()

            @Throws(InterruptedException::class)
            override fun pause(millis: Long) {
                Thread.sleep(millis)
            }
        }

        private fun isLaunchTokenLive(token: String?): Boolean {
            val source = ManagerBinderSource(managerPackageName + PorterProtocol.PROVIDER_AUTHORITY_SUFFIX, 0)
            try {
                return isLaunchTokenLive(token, source, SYSTEM_PACER)
            } finally {
                source.close()
            }
        }

        /**
         * Asks the server whether [token] still belongs to a live record, through the manager's
         * provider. Fails closed, but not on the first null: the manager this launch runs against may be
         * one the platform just killed and this very call is restarting, and the attach that follows
         * waits the same 5s for its state machine. Only availability is retried - a binder that answers
         * "not live" has answered.
         */
        fun isLaunchTokenLive(token: String?, source: BinderSource, pacer: Pacer): Boolean {
            if (token == null) {
                Log.e(TAG, "no --token= to validate")
                return false
            }
            try {
                val started = pacer.now()
                var binder = source.read()
                while (binder == null && pacer.now() - started < LAUNCH_TOKEN_BINDER_TIMEOUT) {
                    pacer.pause(LAUNCH_TOKEN_BINDER_RETRY)
                    binder = source.read()
                }
                if (binder == null) {
                    Log.e(
                        TAG, String.format(
                            Locale.ENGLISH,
                            "no server binder to validate against after %dms", LAUNCH_TOKEN_BINDER_TIMEOUT,
                        ),
                    )
                    return false
                }
                return queryTokenLive(binder, token)
            } catch (tr: Throwable) {
                Log.e(TAG, "failed to validate user service token", tr)
                return false
            }
        }

        @Throws(RemoteException::class)
        private fun queryTokenLive(binder: IBinder, token: String): Boolean {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(PorterProtocol.DESCRIPTOR)
                data.writeString(token)
                if (!binder.transact(UserServiceLaunch.TRANSACTION, data, reply, 0)) return false
                reply.readException()
                return reply.readInt() != 0
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        private fun sendBinder(binder: IBinder, token: String?, retry: Boolean = true): Boolean {
            val name = managerPackageName + PorterProtocol.PROVIDER_AUTHORITY_SUFFIX
            val userId = 0
            var provider: IContentProvider? = null

            try {
                provider = ActivityManagerApis.getContentProviderExternal(name, userId, null, name)
                if (provider == null) {
                    Log.e(TAG, String.format("provider is null %s %d", name, userId))
                    return false
                }
                if (!provider.asBinder().pingBinder()) {
                    Log.e(TAG, String.format("provider is dead %s %d", name, userId))

                    if (retry) {
                        // For unknown reason, sometimes this could happens
                        // Kill Shizuku app and try again could work
                        ActivityManagerApis.forceStopPackageNoThrow(managerPackageName, userId)
                        Log.e(TAG, String.format("kill %s in user %d and try again", managerPackageName, userId))
                        Thread.sleep(1000)
                        return sendBinder(binder, token, false)
                    }
                    return false
                }

                if (!retry) {
                    Log.e(TAG, "retry works")
                }

                val extra = Bundle()
                extra.putBinder(PorterProtocol.DELIVERY_EXTRA_BINDER, binder)
                extra.putString(PorterProtocol.USER_SERVICE_TOKEN, token)

                val reply = IContentProviderCompat.call(
                    provider, null, null, name,
                    PorterProtocol.DELIVERY_METHOD_SEND_USER_SERVICE, null, extra,
                )

                if (reply != null) {
                    Log.i(TAG, String.format("send binder to %s in user %d", managerPackageName, userId))
                    val serverBinder = reply.getBinder(PorterProtocol.DELIVERY_EXTRA_BINDER)

                    if (serverBinder != null && serverBinder.pingBinder()) {
                        shizukuBinder = serverBinder
                        serverBinder.linkToDeath({
                            Log.i(TAG, "exiting...")
                            System.exit(0)
                        }, 0)
                        return true
                    } else {
                        Log.w(TAG, "server binder not received")
                    }
                }

                return false
            } catch (tr: Throwable) {
                Log.e(TAG, String.format("failed send binder to %s in user %d", managerPackageName, userId), tr)
                return false
            } finally {
                if (provider != null) {
                    try {
                        ActivityManagerApis.removeContentProviderExternal(name, null)
                    } catch (tr: Throwable) {
                        Log.w(TAG, "removeContentProviderExternal", tr)
                    }
                }
            }
        }
    }
}
