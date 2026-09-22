package eu.darken.porter.probe

import android.app.Application
import android.os.Build
import android.util.Log
import java.io.FileInputStream
import java.io.IOException

class ProbeApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val name = processName()
        val provider = !name.endsWith(":secondary")
        Log.i("PorterProbe", "$packageName APPLICATION process=$name provider=$provider")
    }

    private companion object {
        fun processName(): String {
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
