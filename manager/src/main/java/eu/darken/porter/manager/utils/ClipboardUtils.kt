package eu.darken.porter.manager.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

object ClipboardUtils {

    fun put(context: Context, text: CharSequence): Boolean = put(context, ClipData.newPlainText("label", text))

    fun put(context: Context, clipData: ClipData): Boolean = try {
        (context.getSystemService("clipboard") as ClipboardManager).setPrimaryClip(clipData)
        true
    } catch (e: Exception) {
        false
    }
}
