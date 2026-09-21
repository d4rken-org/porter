package eu.darken.porter.manager.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import eu.darken.porter.manager.R

/**
 * Created by fytho on 2017/12/15.
 */
object CustomTabsHelper {

    fun interface OnCreateIntentBuilderListener {
        fun onCreateHelpIntentBuilder(context: Context, builder: CustomTabsIntent.Builder)
    }

    var onCreateIntentBuilderListener: OnCreateIntentBuilderListener? = null

    fun createBuilder(): CustomTabsIntent.Builder = CustomTabsIntent.Builder().setShowTitle(true)

    fun launchHelp(context: Context, uri: Uri): Boolean {
        val builder = createBuilder()

        onCreateIntentBuilderListener?.onCreateHelpIntentBuilder(context, builder)

        val uriBuilder = uri.buildUpon()
        if ((context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_YES) > 0) {
            uriBuilder.appendQueryParameter("night", "1")
        }

        return launchUrl(context, builder.build(), uriBuilder.build())
    }

    fun launchUrl(context: Context, uri: Uri): Boolean = launchUrl(context, createBuilder().build(), uri)

    private fun launchUrl(context: Context, customTabsIntent: CustomTabsIntent, uri: Uri): Boolean = try {
        customTabsIntent.launchUrl(context, uri)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

    fun launchUrlOrCopy(context: Context, url: String) {
        val uri = Uri.parse(url)
        if (launchHelp(context, uri)) return

        val intent = Intent(Intent.ACTION_VIEW)
        intent.data = uri

        try {
            context.startActivity(intent)
        } catch (tr: Throwable) {
            try {
                ClipboardUtils.put(context, url)

                Toast.makeText(
                    context,
                    context.getString(R.string.dialog_cannot_open_browser_title) + "\n" + context.getString(R.string.toast_copied_to_clipboard),
                    Toast.LENGTH_LONG,
                ).show()
            } catch (ignored: Throwable) {
            }
        }
    }
}
