package eu.darken.porter.common.util

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.security.MessageDigest
import java.util.Collections

/**
 * Who signed a package, as the SHA-256 of each signing certificate in lowercase hex. The server
 * records it when a user service is bound and the service's host checks it before loading any code,
 * so both read it through this one definition.
 */
object SignerDigests {

    private const val DIGEST = "SHA-256"
    private val HEX = "0123456789abcdef".toCharArray()

    /** What a [PackageInfo] has to be fetched with for [of] to answer. */
    @Suppress("DEPRECATION")
    fun lookupFlags(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES.toLong()
        else PackageManager.GET_SIGNATURES.toLong()

    /** The signers of [packageInfo], or null when the answer carries none. */
    @Suppress("DEPRECATION")
    fun of(packageInfo: PackageInfo): Set<String>? {
        val signingInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) packageInfo.signingInfo else null
        val digests = if (signingInfo != null) of(signingInfo.apkContentsSigners) else of(packageInfo.signatures)
        return if (digests.isEmpty()) null else digests
    }

    fun of(signatures: Array<Signature>?): Set<String> {
        if (signatures == null || signatures.isEmpty()) return emptySet()
        val digests = LinkedHashSet<String>(signatures.size)
        for (signature in signatures) {
            val digest = of(signature)
            if (digest != null) digests.add(digest)
        }
        return Collections.unmodifiableSet(digests)
    }

    fun of(signature: Signature?): String? {
        if (signature == null) return null
        try {
            val hash = MessageDigest.getInstance(DIGEST).digest(signature.toByteArray())
            val out = CharArray(hash.size * 2)
            for (i in hash.indices) {
                out[i * 2] = HEX[(hash[i].toInt() shr 4) and 0xf]
                out[i * 2 + 1] = HEX[hash[i].toInt() and 0xf]
            }
            return String(out)
        } catch (tr: Throwable) {
            return null
        }
    }
}
