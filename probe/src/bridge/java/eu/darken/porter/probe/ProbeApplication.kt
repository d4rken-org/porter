package eu.darken.porter.probe

import android.app.Application
import android.os.Build
import android.util.Log
import eu.darken.porter.sdk.PorterApiProvider
import java.io.FileInputStream
import java.io.IOException

class ProbeApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val name = processName()
        val provider = !name.endsWith(":secondary")
        Log.i("PorterProbe", "$packageName APPLICATION process=$name provider=$provider")
    }

    companion object {
        // Content providers are installed before Application.onCreate, and the provider only
        // announces a delivery once this is set. A class initializer is the earliest point this
        // process has.
        init {
            PorterApiProvider.enableMultiProcessSupport(!processName().endsWith(":secondary"))
        }

        private fun processName(): String {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return getProcessName()
            try {
                FileInputStream("/proc/self/cmdline").use { cmdline ->
                    val name = StringBuilder()
                    var read = cmdline.read()
                    while (read > 0) {
                        name.append(read.toChar())
                        read = cmdline.read()
                    }
                    return name.toString()
                }
            } catch (e: IOException) {
                // Guessing here would let the secondary process claim the provider role.
                throw IllegalStateException("cannot read this process's name", e)
            }
        }
    }
}
