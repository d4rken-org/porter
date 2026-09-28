package eu.darken.porter.manager.updater

import android.content.Context
import java.io.File

/** A debug build answers update checks from `updater-override.json` in its external files directory. */
object UpdaterDebugOverride {
    fun releaseJson(context: Context): String? {
        val directory = context.getExternalFilesDir(null) ?: return null
        val file = File(directory, "updater-override.json")
        return if (file.exists()) file.readText() else null
    }
}
