package eu.darken.porter.shell

import android.app.ActivityManagerNative
import android.app.IActivityManager
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.RemoteException
import android.os.ServiceManager
import android.system.Os
import android.text.TextUtils
import dalvik.system.BaseDexClassLoader
import java.io.File
import rikka.hidden.compat.PackageManagerApis
import stub.dalvik.system.VMRuntimeHidden
import kotlin.system.exitProcess

/** Launched by app_process from the porsh script as `eu.darken.porter.shell.PorterShellLoader`. */
object PorterShellLoader {

    private lateinit var args: Array<String>
    private lateinit var callingPackage: String
    private lateinit var handler: Handler

    private val receiverBinder: Binder = object : Binder() {

        @Throws(RemoteException::class)
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == 1) {
                val binder = data.readStrongBinder()

                val sourceDir = data.readString()
                if (binder != null) {
                    handler.post { onBinderReceived(binder, sourceDir!!) }
                } else {
                    System.err.println("Server is not running")
                    System.err.flush()
                    exitProcess(1)
                }
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    @Throws(Exception::class)
    private fun requestForBinder() {
        val data = Bundle()
        data.putBinder("binder", receiverBinder)

        var managerApplicationId = System.getenv("MANAGER_APPLICATION_ID")
        if (TextUtils.isEmpty(managerApplicationId) || "MANAGER_PKG" == managerApplicationId) {
            managerApplicationId = BuildConfig.MANAGER_APPLICATION_ID
        }

        val intent = Intent("eu.darken.porter.intent.action.REQUEST_BINDER")
            .setPackage(managerApplicationId)
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            .putExtra("data", data)

        val amBinder = ServiceManager.getService("activity")
        val am: IActivityManager = if (Build.VERSION.SDK_INT >= 26) {
            IActivityManager.Stub.asInterface(amBinder)
        } else {
            ActivityManagerNative.asInterface(amBinder)
        }

        // broadcastIntent will fail on Android 8.x
        //  com.android.server.am.ActivityManagerService.isInstantApp(ActivityManagerService.java:18547)
        //  com.android.server.am.ActivityManagerService.broadcastIntentLocked(ActivityManagerService.java:18972)
        //  com.android.server.am.ActivityManagerService.broadcastIntent(ActivityManagerService.java:19703)
        //
        try {
            am.broadcastIntent(
                null, intent, null, null, 0, null, null,
                null, -1, null, true, false, 0,
            )
        } catch (e: Throwable) {
            if ((Build.VERSION.SDK_INT != Build.VERSION_CODES.O && Build.VERSION.SDK_INT != Build.VERSION_CODES.O_MR1) ||
                e.message != "Calling application did not provide package name"
            ) {
                throw e
            }

            System.err.println("broadcastIntent fails on Android 8.0 or 8.1, fallback to startActivity")
            System.err.flush()

            val activityIntent = Intent.createChooser(
                Intent("eu.darken.porter.intent.action.REQUEST_BINDER")
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
                    .putExtra("data", data),
                "Request binder from Shizuku",
            )

            am.startActivityAsUser(null, callingPackage, activityIntent, null, null, null, 0, 0, null, null, Os.getuid() / 100000)
        }
    }

    private fun onBinderReceived(binder: IBinder, sourceDir: String) {
        val base = sourceDir.substring(0, sourceDir.lastIndexOf('/'))
        var librarySearchPath = base + "/lib/" + VMRuntimeHidden.getRuntime().vmInstructionSet()
        val systemLibrarySearchPath = System.getProperty("java.library.path")
        if (!TextUtils.isEmpty(systemLibrarySearchPath)) {
            librarySearchPath += File.pathSeparatorChar + systemLibrarySearchPath
        }

        try {
            if (args.isEmpty()) {
                println("Entering shell...")
            }
            val classLoader = BaseDexClassLoader(sourceDir, null, librarySearchPath, ClassLoader.getSystemClassLoader())
            val cls = classLoader.loadClass("eu.darken.porter.manager.shell.Shell")
            cls.getDeclaredMethod("main", Array<String>::class.java, String::class.java, IBinder::class.java, Handler::class.java)
                .invoke(null, args, callingPackage, binder, handler)
        } catch (tr: ClassNotFoundException) {
            System.err.println("Class not found")
            System.err.println("Make sure the Porter app is installed and up to date")
            System.err.flush()
            exitProcess(1)
        } catch (tr: Throwable) {
            tr.printStackTrace(System.err)
            System.err.flush()
            exitProcess(1)
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        PorterShellLoader.args = args

        val packageName: String
        val pkg = PackageManagerApis.getPackagesForUidNoThrow(Os.getuid())
        if (pkg.size == 1) {
            packageName = pkg[0]
        } else {
            val configured = System.getenv("PORSH_APPLICATION_ID")
            if (TextUtils.isEmpty(configured) || "PKG" == configured) {
                abort("PORSH_APPLICATION_ID is not set, please set this environment variable in porsh to the package name of the terminal app")
            }
            packageName = configured
        }

        callingPackage = packageName

        if (Looper.getMainLooper() == null) {
            Looper.prepareMainLooper()
        }

        handler = Handler(Looper.getMainLooper())

        try {
            requestForBinder()
        } catch (tr: Throwable) {
            tr.printStackTrace(System.err)
            System.err.flush()
            exitProcess(1)
        }

        handler.postDelayed(
            {
                abort(
                    String.format(
                        "Request timeout. " +
                            "Check that MANAGER_APPLICATION_ID is eu.darken.porter and Porter is running.\n" +
                            "The connection between the current app (%1\$s) and Porter may be blocked by your system. " +
                            "Please disable all battery optimization features for both current app (%1\$s) and Porter.",
                        packageName,
                    ),
                )
            },
            5000,
        )

        Looper.loop()
        exitProcess(0)
    }

    private fun abort(message: String): Nothing {
        System.err.println(message)
        System.err.flush()
        exitProcess(1)
    }
}
