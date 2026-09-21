package eu.darken.porter.probe

import android.os.Process
import java.io.File
import java.io.IOException
import kotlin.system.exitProcess

class ProbeService : IProbe.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    override fun uid(): Int = Process.myUid()

    override fun readFile(path: String): String? = try {
        File(path).bufferedReader().use { it.readLine() }
    } catch (e: IOException) {
        e.toString()
    }
}
