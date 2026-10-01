package eu.darken.porter.privileged

import androidx.annotation.Keep
import com.google.gson.Gson
import eu.darken.porter.protocol.PorterProtocol

internal object LegacyAccessImport {
    const val ALLOW = 2
    const val DENY = 4
    const val MAX_ENTRIES = 500
    private val GSON = Gson()

    interface Resolver {
        @Throws(Exception::class)
        fun resolve(packageName: String): App?

        fun hasPorterDecision(uid: Int): Boolean
    }

    class App {
        var packageName: String? = null
        var label: String? = null
        var certificate: String? = null
        var uid = 0
        var granted = false
        var exclusiveUid = false
        var legacy = false
    }

    @Keep
    class Decision {
        var packageName: String? = null
        var label: String? = null
        var certificate: String? = null
        var uid = 0
        var flags = 0
    }

    @Keep
    class Source {
        var version = 0
        var packages: List<Entry?>? = null
    }

    @Keep
    class Entry {
        var uid = 0
        var flags = 0
        var packages: List<String>? = null
    }

    enum class SkipReason {
        NULL_ENTRY,
        UID_OUT_OF_RANGE,
        DUPLICATE_UID,
        PACKAGES_NOT_SINGLE,
        UNSUPPORTED_FLAGS,
        PORTER_DECISION_EXISTS,
        NOT_CLIENT_PACKAGE,
        PACKAGE_UNRESOLVED,
        UID_CHANGED,
        UID_NOT_EXCLUSIVE,
        NO_API_V23_REQUEST,
        NO_CERTIFICATE,
        ALLOW_NOT_GRANTED,
        DENY_GRANTED,
    }

    /** @param skipped called once per left-out entry with its uid (-1 for a null entry) and its only package, if any */
    @Throws(Exception::class)
    fun preview(
        json: String,
        resolver: Resolver,
        skipped: (uid: Int, packageName: String?, reason: SkipReason) -> Unit = { _, _, _ -> },
    ): List<Decision> {
        val source = GSON.fromJson(json, Source::class.java)
        val packages = source?.packages
        if (source == null || source.version != 2 || packages == null || packages.size > MAX_ENTRIES)
            throw IllegalArgumentException("Unsupported Shizuku access database")
        val result = ArrayList<Decision>()
        val duplicates = HashSet<Int>()
        val seen = HashSet<Int>()
        for (entry in packages) {
            if (entry != null && !seen.add(entry.uid)) duplicates.add(entry.uid)
        }
        for (entry in packages) {
            if (entry == null) {
                skipped(-1, null, SkipReason.NULL_ENTRY)
                continue
            }
            val uid = entry.uid
            val entryPackages = entry.packages
            val name: String? = if (entryPackages != null && entryPackages.size == 1) entryPackages[0] else null
            if (uid < 10000 || uid >= 100000) {
                skipped(uid, name, SkipReason.UID_OUT_OF_RANGE)
                continue
            }
            if (duplicates.contains(uid)) {
                skipped(uid, name, SkipReason.DUPLICATE_UID)
                continue
            }
            if (entryPackages == null || entryPackages.size != 1) {
                skipped(uid, name, SkipReason.PACKAGES_NOT_SINGLE)
                continue
            }
            if (entry.flags != ALLOW && entry.flags != DENY) {
                skipped(uid, name, SkipReason.UNSUPPORTED_FLAGS)
                continue
            }
            if (resolver.hasPorterDecision(uid)) {
                skipped(uid, name, SkipReason.PORTER_DECISION_EXISTS)
                continue
            }
            if (name == null || !clientPackage(name)) {
                skipped(uid, name, SkipReason.NOT_CLIENT_PACKAGE)
                continue
            }
            val app = resolver.resolve(name)
            if (app == null) {
                skipped(uid, name, SkipReason.PACKAGE_UNRESOLVED)
                continue
            }
            if (app.uid != uid) {
                skipped(uid, name, SkipReason.UID_CHANGED)
                continue
            }
            if (!app.exclusiveUid) {
                skipped(uid, name, SkipReason.UID_NOT_EXCLUSIVE)
                continue
            }
            if (!app.legacy) {
                skipped(uid, name, SkipReason.NO_API_V23_REQUEST)
                continue
            }
            if (app.certificate == null) {
                skipped(uid, name, SkipReason.NO_CERTIFICATE)
                continue
            }
            if (entry.flags == ALLOW && !app.granted) {
                skipped(uid, name, SkipReason.ALLOW_NOT_GRANTED)
                continue
            }
            if (entry.flags == DENY && app.granted) {
                skipped(uid, name, SkipReason.DENY_GRANTED)
                continue
            }
            val decision = Decision()
            decision.packageName = name
            decision.label = app.label
            decision.certificate = app.certificate
            decision.uid = app.uid
            decision.flags = entry.flags
            result.add(decision)
        }
        return result
    }

    fun decode(json: String): List<Decision> {
        val decisions = GSON.fromJson(json, Array<Decision>::class.java)
        if (decisions == null || decisions.size > MAX_ENTRIES) throw IllegalArgumentException("Invalid access snapshot")
        return decisions.asList()
    }

    @Throws(Exception::class)
    fun canApply(decision: Decision?, resolver: Resolver): Boolean {
        val packageName = decision?.packageName
        if (decision == null || packageName == null || !clientPackage(packageName) || decision.uid < 10000 || decision.uid >= 100000
            || decision.certificate == null || (decision.flags != ALLOW && decision.flags != DENY)
            || resolver.hasPorterDecision(decision.uid)) return false
        val app = resolver.resolve(packageName)
        return app != null && app.uid == decision.uid && app.exclusiveUid && app.legacy
            && decision.certificate == app.certificate
    }

    fun encode(decisions: List<Decision>): String = GSON.toJson(decisions)

    private fun clientPackage(name: String?): Boolean =
        name != null && name.isNotEmpty() && name != PorterProtocol.MANAGER_APPLICATION_ID && name != ServerConstants.COMPAT_APPLICATION_ID
}
