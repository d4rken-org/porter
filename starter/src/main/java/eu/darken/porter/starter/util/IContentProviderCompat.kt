package eu.darken.porter.starter.util

import android.content.AttributionSource
import android.content.IContentProvider
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.system.Os

object IContentProviderCompat {

    @Throws(RemoteException::class)
    fun call(
        provider: IContentProvider,
        attributeTag: String?,
        callingPkg: String?,
        authority: String?,
        method: String?,
        arg: String?,
        extras: Bundle?,
    ): Bundle? {
        val reply: Bundle?
        if (Build.VERSION.SDK_INT >= 31) {
            reply = try {
                provider.call(AttributionSource.Builder(Os.getuid()).setAttributionTag(attributeTag).setPackageName(callingPkg).build(), authority, method, arg, extras)
            } catch (e: LinkageError) {
                // SDK 31 and above whose IContentProvider still carries the Android 11 signature
                // instead of the AttributionSource one.
                provider.call(callingPkg, attributeTag, authority, method, arg, extras)
            }
        } else if (Build.VERSION.SDK_INT >= 30) {
            reply = provider.call(callingPkg, attributeTag, authority, method, arg, extras)
        } else if (Build.VERSION.SDK_INT >= 29) {
            reply = provider.call(callingPkg, authority, method, arg, extras)
        } else {
            reply = provider.call(callingPkg, method, arg, extras)
        }

        return reply
    }
}
