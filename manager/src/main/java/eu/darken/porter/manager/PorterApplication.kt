package eu.darken.porter.manager

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.topjohnwu.superuser.Shell
import eu.darken.porter.common.util.BuildUtils
import eu.darken.porter.manager.ktx.logd
import eu.darken.porter.manager.service.WatchdogService
import eu.darken.porter.manager.utils.PorterStateMachine
import eu.darken.porter.manager.utils.PorterSystemApis
import org.lsposed.hiddenapibypass.HiddenApiBypass

class PorterApplication : Application() {

    companion object {

        init {
            logd("PorterApplication", "init")

            Shell.setDefaultBuilder(Shell.Builder.create().setFlags(Shell.FLAG_REDIRECT_STDERR))
            if (Build.VERSION.SDK_INT >= 28) {
                HiddenApiBypass.setHiddenApiExemptions("")
            }
            if (BuildUtils.atLeast30()) {
                System.loadLibrary("adb")
            }
        }

        lateinit var application: PorterApplication
            private set

        lateinit var appContext: Context
            private set

    }

    private fun init(context: Context) {
        PorterSettings.initialize(context)
        if (LocaleMigration.needsClear(PorterSettings.getPreferences())) {
            getSystemService(android.app.LocaleManager::class.java).applicationLocales = android.os.LocaleList.getEmptyLocaleList()
            LocaleMigration.markDone(PorterSettings.getPreferences())
        }
        PorterSystemApis.instance.attachToSystemServices()
        eu.darken.porter.manager.support.DebugRecorder.get(context).attach()
        // After the observers it can drive: the sticky binder-received listener fires
        // synchronously here when the binder is already up.
        PorterStateMachine.instance.attachToShizuku()
        AppCompatDelegate.setDefaultNightMode(PorterSettings.getNightMode())

        if(PorterSettings.getWatchdog()) WatchdogService.start(context)
    }

    override fun onCreate() {
        super.onCreate()
        application = this
        appContext = applicationContext
        init(this)
    }

}
