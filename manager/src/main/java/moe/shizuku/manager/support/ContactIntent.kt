package moe.shizuku.manager.support

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import moe.shizuku.manager.R

internal fun contactIntent(context: Context, category: String, description: String, expected: String, bug: Boolean, uri: Uri?): Intent {
                val intent = Intent(if (uri == null) Intent.ACTION_SENDTO else Intent.ACTION_SEND).apply {
                    if (uri == null) data = Uri.parse("mailto:support@darken.eu") else {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri("Porter debug log", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    putExtra(Intent.EXTRA_EMAIL, arrayOf("support@darken.eu"))
                    putExtra(Intent.EXTRA_SUBJECT, "[Porter][$category] " + description.lineSequence().first().take(80))
                    putExtra(Intent.EXTRA_TEXT, buildString {
                        appendLine(description)
                        if (bug) appendLine("\nExpected behavior:\n$expected")
                        appendLine("\n${DebugRecorder.deviceDetails()}")
                    })
                }
                val mailtoApps = context.packageManager.queryIntentActivities(
                    Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:support@darken.eu")), 0,
                )
                val emailPackages = mailtoApps.map { it.activityInfo.packageName }.toSet()
                val handlers = if (uri == null) mailtoApps else context.packageManager.queryIntentActivities(intent, 0)
                    .filter { it.activityInfo.packageName in emailPackages }
                val emailApps = handlers.map { result ->
                    Intent(intent).setComponent(ComponentName(result.activityInfo.packageName, result.activityInfo.name))
                }.distinctBy { it.component }
                if (emailApps.isEmpty()) throw android.content.ActivityNotFoundException(context.getString(R.string.porter_contact_no_email))
    return Intent.createChooser(emailApps.first(), context.getString(R.string.porter_contact_send)).apply {
        putExtra(Intent.EXTRA_INITIAL_INTENTS, emailApps.drop(1).toTypedArray())
    }
}
