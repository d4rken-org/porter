package eu.darken.porter.privileged.util

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Build
import java.security.MessageDigest
import java.util.Collections
import rikka.shizuku.server.util.UserHandleCompat

/**
 * One package-manager lookup per `(package, user)`, answering three things rather than two:
 * the package is there, the package is not there, or the question was not answered.
 *
 * `PackageManagerApis.getApplicationInfoNoThrow` catches [Throwable] and returns
 * null, so "uninstalled" and "the call failed" arrive as one observation. A timer that tears down a
 * privileged service on that reading kills it on the first transient failure.
 */
object PackageIdentity {

    private const val DIGEST = "SHA-256"
    private val HEX = "0123456789abcdef".toCharArray()

    enum class State { PRESENT, ABSENT, LOOKUP_FAILED }

    /** What one user answered. Only produced for [State.PRESENT]. */
    class Observed(
        val userId: Int,
        val appId: Int,
        /** One digest per signer, so a multi-signer package compares as a set. */
        val signerDigests: Set<String>,
        val multipleSigners: Boolean,
        /** Null below API 28 and for multi-signer packages, where rotation is not possible. */
        val signingHistory: Array<Signature>?,
    ) {
        override fun toString(): String = "user=$userId appId=$appId signers=$signerDigests"
    }

    class Result private constructor(
        val state: State,
        val observed: Observed?,
        val cause: Throwable?,
    ) {
        companion object {
            fun present(observed: Observed): Result = Result(State.PRESENT, observed, null)

            fun absent(): Result = Result(State.ABSENT, null, null)

            fun failed(cause: Throwable): Result = Result(State.LOOKUP_FAILED, null, cause)
        }
    }

    /**
     * What a record remembers about the installation it was created for: fixed at creation and never
     * updated. Both components are one per package name device-wide, so a record, which serves one
     * Android user, is kept alive by any user holding a matching installation and is replaced when
     * any user's installation stops matching.
     */
    class Identity(
        val packageName: String,
        val appId: Int,
        val signerDigests: Set<String>,
    ) {
        /**
         * Whether [observed] is the same installation. A rotated signing key still matches
         * because the recorded certificate remains in the lineage; a different signing authority
         * does not, in any user.
         */
        fun matches(observed: Observed?): Boolean {
            if (observed == null || observed.appId != appId) return false
            if (signerDigests == observed.signerDigests) return true
            // Proof of rotation is an API 28 feature and only exists for a single signer, on either
            // side: a recorded pair that lost one of its signing authorities is a different
            // installation, not a rotation. Below API 28, and for multi-signer packages, the platform
            // rejects an update whose key differs, so a changed signature can only have come from an
            // uninstall and a foreign reinstall.
            if (signerDigests.size != 1) return false
            val history = observed.signingHistory
            if (observed.multipleSigners || history == null) return false
            for (past in history) {
                if (signerDigests.contains(digestOf(past))) return true
            }
            return false
        }

        override fun toString(): String = "$packageName appId=$appId signers=$signerDigests"
    }

    /**
     * `MATCH_UNINSTALLED_PACKAGES` is deliberate: without it a package hidden by `pm
     * hide` or by admin policy answers null and reads as an uninstall. With it, presence is decided
     * by the per-user installed bit, which a hidden package keeps and an `uninstall -k`
     * residue does not.
     */
    @Suppress("DEPRECATION")
    fun lookupFlags(): Long {
        val flags = PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return flags or PackageManager.GET_SIGNING_CERTIFICATES.toLong()
        }
        return flags or PackageManager.GET_SIGNATURES.toLong()
    }

    fun of(packageName: String, userId: Int): Result {
        val packageInfo: PackageInfo?
        try {
            // Through the compat layer, not around it: on the platform whose hidden signature
            // changed, going direct would report LOOKUP_FAILED forever while startup succeeded
            // through the reflection fallback, silently disabling cleanup.
            packageInfo = Android17Compat.getPackageInfoOrThrow(packageName, lookupFlags(), userId)
        } catch (tr: Throwable) {
            return Result.failed(tr)
        }
        return classify(packageInfo, userId)
    }

    /** Turns an already-fetched [PackageInfo] into a verdict for one user. */
    fun classify(packageInfo: PackageInfo?, userId: Int): Result {
        // null for a missing package is the hidden IPackageManager contract; NameNotFoundException
        // is higher-level PackageManager behaviour and does not reach here.
        if (packageInfo == null) return Result.absent()
        val applicationInfo = packageInfo.applicationInfo
            ?: return Result.failed(IllegalStateException("no application info for " + packageInfo.packageName))
        if ((applicationInfo.flags and ApplicationInfo.FLAG_INSTALLED) == 0) {
            return Result.absent()
        }
        val observed = observe(packageInfo, userId)
            // An answer that carries no signer is not evidence of a different signer.
            ?: return Result.failed(IllegalStateException("no signatures for " + packageInfo.packageName))
        return Result.present(observed)
    }

    /**
     * The signers of a [PackageInfo] already fetched with [lookupFlags], or null when
     * the answer carries none. Both readers share it, so neither has to supply a user id to learn
     * who signed an APK.
     */
    @Suppress("DEPRECATION")
    private fun signerDigestsOf(packageInfo: PackageInfo): Set<String>? {
        val signingInfo = signingInfoOf(packageInfo)
        val digests = if (signingInfo != null)
            digestsOf(signingInfo.apkContentsSigners)
        else
            digestsOf(packageInfo.signatures)
        return if (digests.isEmpty()) null else digests
    }

    /** Null below API 28, where the lineage this carries does not exist. */
    private fun signingInfoOf(packageInfo: PackageInfo): SigningInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) packageInfo.signingInfo else null

    /** Reads an identity out of a [PackageInfo] already fetched with [lookupFlags]. */
    fun observe(packageInfo: PackageInfo?, userId: Int): Observed? {
        val applicationInfo = packageInfo?.applicationInfo ?: return null
        val digests = signerDigestsOf(packageInfo) ?: return null
        val appId = UserHandleCompat.getAppId(applicationInfo.uid)

        val signingInfo = signingInfoOf(packageInfo)
            ?: return Observed(userId, appId, digests, digests.size > 1, null)
        // History is null for a multi-signer package, where rotation is not possible, so there the
        // whole signer set is the identity.
        val multiple = signingInfo.hasMultipleSigners()
        return Observed(
            userId, appId, digests, multiple,
            if (multiple) null else signingInfo.signingCertificateHistory,
        )
    }

    /**
     * The identity a record remembers, taken from the same lookup that authorised the bind. Null
     * when no signer could be read: [classify] calls that same reading a failure, and an
     * identity carrying no signer could never match anything again.
     */
    fun identityOf(packageInfo: PackageInfo?): Identity? {
        val applicationInfo = packageInfo?.applicationInfo ?: return null
        val digests = signerDigestsOf(packageInfo) ?: return null
        return Identity(packageInfo.packageName, UserHandleCompat.getAppId(applicationInfo.uid), digests)
    }

    private fun digestsOf(signatures: Array<Signature>?): Set<String> {
        if (signatures == null || signatures.isEmpty()) return emptySet()
        val digests = LinkedHashSet<String>(signatures.size)
        for (signature in signatures) {
            val digest = digestOf(signature)
            if (digest != null) digests.add(digest)
        }
        return Collections.unmodifiableSet(digests)
    }

    private fun digestOf(signature: Signature?): String? {
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

    /** Whether two users answered with the same installation, which they must for a stable scan. */
    fun sameInstallation(first: Observed, second: Observed): Boolean =
        first.appId == second.appId && first.signerDigests == second.signerDigests
}
