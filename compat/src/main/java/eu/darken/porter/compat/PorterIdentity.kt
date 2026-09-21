package eu.darken.porter.compat

import android.content.Context
import android.content.pm.PackageManager

internal object PorterIdentity {
    const val PACKAGE = "eu.darken.porter"

    fun isInstalled(context: Context): Boolean =
        context.packageManager.checkSignatures(context.packageName, PACKAGE) == PackageManager.SIGNATURE_MATCH
}
