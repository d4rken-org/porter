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

    @Throws(Exception::class)
    fun preview(json: String, resolver: Resolver): List<Decision> {
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
            val entryPackages = entry?.packages
            if (entry == null || entry.uid < 10000 || entry.uid >= 100000 || duplicates.contains(entry.uid)
                || entryPackages == null || entryPackages.size != 1
                || (entry.flags != ALLOW && entry.flags != DENY) || resolver.hasPorterDecision(entry.uid)) continue
            val name = entryPackages[0]
            if (!clientPackage(name)) continue
            val app = resolver.resolve(name)
            if (app == null || app.uid != entry.uid || !app.exclusiveUid || !app.legacy
                || app.certificate == null || (entry.flags == ALLOW) != app.granted) continue
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
