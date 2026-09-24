package eu.darken.porter.privileged

import android.os.Process
import android.system.Os
import android.util.AtomicFile
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import eu.darken.porter.privileged.util.PackageIdentity
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.Reader
import rikka.hidden.compat.PackageManagerApis
import rikka.shizuku.server.ConfigManager
import rikka.shizuku.server.util.Logger
import rikka.shizuku.server.util.UserHandleCompat

open class ShizukuConfigManager : ConfigManager() {

    private val config: ShizukuConfig = load()

    init {
        var changed = false

        if (config.packages == null) {
            config.packages = ArrayList()
            changed = true
        }

        for (entry in ArrayList(packagesLocked())) {
            if (entry.packages == null) {
                entry.packages = ArrayList()
            }
            val entryPackages = entry.packages!!

            val packages = PackageManagerApis.getPackagesForUidNoThrow(entry.uid)
            if (packages.isEmpty()) {
                LOGGER.i("remove config for uid %d since it has gone", entry.uid)
                packagesLocked().remove(entry)
                changed = true
                continue
            }

            var packagesChanged = true

            for (packageName in entryPackages) {
                if (packages.contains(packageName)) {
                    packagesChanged = false
                    break
                }
            }

            val rawSize = entryPackages.size
            val s = LinkedHashSet(entryPackages)
            entryPackages.clear()
            entryPackages.addAll(s)
            val shrunkSize = entryPackages.size
            if (shrunkSize < rawSize) {
                LOGGER.w("entry.packages has duplicate! Shrunk. (%d -> %d)", rawSize, shrunkSize)
            }

            if (packagesChanged) {
                LOGGER.i("remove config for uid %d since the packages for it changed", entry.uid)
                packagesLocked().remove(entry)
                changed = true
            } else if (entry.signers == null) {
                // A decision from before signers were recorded learns them now, from the installation
                // it has been kept for, rather than from whichever process attaches first.
                val signers = signersOf(entry.uid, entryPackages.filter { it in packages })
                if (signers != null) {
                    entry.signers = signers.toMutableList()
                    changed = true
                }
            }
        }

        if (changed) {
            if (!persistLocked()) LOGGER.w("failed to save reconciled package config")
        }
    }

    /** The list the constructor made sure exists. */
    private fun packagesLocked(): MutableList<ShizukuConfig.PackageEntry> = config.packages!!

    val isAccessPaused: Boolean
        @Synchronized get() = config.accessPaused

    @Synchronized
    fun setAccessPaused(paused: Boolean) {
        if (config.accessPaused == paused) return
        val previous = config.accessPaused
        config.accessPaused = paused
        if (!persistLocked()) {
            // Realign memory with disk so the caller's same-value retry is not swallowed by the
            // early return above.
            config.accessPaused = previous
            throw IllegalStateException("App access pause could not be saved")
        }
    }

    internal open fun writeConfig(): Boolean = write(config)

    private fun persistLocked(): Boolean = writeConfig()

    @Synchronized
    internal fun persistImport() {
        if (!write(config)) throw IllegalStateException("App access could not be saved; retry the import")
    }

    private fun findLocked(uid: Int): ShizukuConfig.PackageEntry? {
        for (entry in packagesLocked()) {
            if (uid == entry.uid) {
                return entry
            }
        }
        return null
    }

    fun allowedUids(): List<Int> {
        synchronized(this) {
            val uids = ArrayList<Int>()
            for (entry in packagesLocked()) {
                if (entry.isAllowed() || entry.isPendingCompanion()) uids.add(entry.uid)
            }
            return uids
        }
    }

    override fun find(uid: Int): ShizukuConfig.PackageEntry? {
        synchronized(this) {
            return findLocked(uid)
        }
    }

    private fun updateLocked(uid: Int, packages: List<String>?, mask: Int, values: Int) {
        var entry = findLocked(uid)
        if (entry == null) {
            entry = ShizukuConfig.PackageEntry(uid, mask and values)
            packagesLocked().add(entry)
        } else {
            val newValue = (entry.flags and mask.inv()) or (mask and values)
            if (newValue == entry.flags) {
                return
            }
            entry.flags = newValue
        }
        if (packages != null) {
            val entryPackages = entry.packages!!
            for (packageName in packages) {
                if (entryPackages.contains(packageName)) {
                    continue
                }
                entryPackages.add(packageName)
            }
            if (entry.signers == null) entry.signers = signersOf(uid, packages)?.toMutableList()
        }
        if (!persistLocked()) LOGGER.w("failed to save config for uid %d", uid)
    }

    /**
     * Whether the decision stored for [uid] was made for the installation [packageName] attaches from.
     * A uid outlives its installation: once a package leaves it, a later install can be handed the same
     * uid. An entry whose uid holds none of its packages any more, or whose signers changed, is dropped;
     * an installation that cannot be read is not trusted, but its entry is kept.
     */
    fun verifiedForAttach(uid: Int, packageName: String): Boolean {
        val entry = find(uid) ?: return false
        val recorded = synchronized(this) { ArrayList(entry.packages.orEmpty()) }
        if (PackageManagerApis.getPackagesForUidNoThrow(uid).none { it in recorded }) {
            forget(entry, "uid %d no longer holds %s", uid, recorded)
            return false
        }
        val current = PackageIdentity.of(packageName, UserHandleCompat.getUserId(uid))
        val observed = current.observed ?: return false
        synchronized(this) {
            if (findLocked(uid) !== entry) return false
            val signers = entry.signers
            if (signers == null) {
                entry.signers = observed.signerDigests.toMutableList()
                if (!persistLocked()) LOGGER.w("failed to save signers for uid %d", uid)
                return true
            }
            if (PackageIdentity.Identity(packageName, UserHandleCompat.getAppId(uid), signers.toSet()).matches(observed)) return true
        }
        forget(entry, "%s in uid %d is signed by someone else", packageName, uid)
        return false
    }

    private fun forget(entry: ShizukuConfig.PackageEntry, fmt: String, vararg args: Any?) {
        synchronized(this) {
            if (!packagesLocked().remove(entry)) return
            LOGGER.w("forgetting a decision: $fmt", *args)
            if (!persistLocked()) LOGGER.w("failed to save config after forgetting uid %d", entry.uid)
        }
    }

    private fun signersOf(uid: Int, packages: List<String>): Set<String>? {
        val userId = UserHandleCompat.getUserId(uid)
        for (packageName in packages) {
            val observed = PackageIdentity.of(packageName, userId).observed ?: continue
            if (observed.appId == UserHandleCompat.getAppId(uid)) return observed.signerDigests
        }
        return null
    }

    override fun update(uid: Int, packages: List<String>?, mask: Int, values: Int) {
        synchronized(this) {
            updateLocked(uid, packages, mask, values)
        }
    }

    private fun removeLocked(uid: Int) {
        val entry = findLocked(uid) ?: return
        packagesLocked().remove(entry)
        if (!persistLocked()) LOGGER.w("failed to save config after removing uid %d", uid)
    }

    override fun remove(uid: Int) {
        synchronized(this) {
            removeLocked(uid)
        }
    }

    companion object {

        private val LOGGER = Logger("ConfigManager")

        private val GSON_IN: Gson = GsonBuilder()
            .create()
        private val GSON_OUT: Gson = GsonBuilder()
            .setVersion(ShizukuConfig.LATEST_VERSION.toDouble())
            .create()

        private val FILE = File("/data/user_de/0/com.android.shell/porter.json")
        private val ATOMIC_FILE = AtomicFile(FILE)

        fun load(): ShizukuConfig {
            val stream: FileInputStream
            try {
                stream = ATOMIC_FILE.openRead()
            } catch (e: FileNotFoundException) {
                LOGGER.i("no existing config file " + ATOMIC_FILE.baseFile + "; starting empty")
                return ShizukuConfig()
            }

            try {
                return read(InputStreamReader(stream))
            } catch (tr: Throwable) {
                LOGGER.w(tr, "load config")
            } finally {
                try {
                    stream.close()
                } catch (e: IOException) {
                    LOGGER.w("failed to close: $e")
                }
            }
            return ShizukuConfig()
        }

        internal fun read(reader: Reader): ShizukuConfig {
            val json = JsonParser.parseReader(reader)
            // Read off the tree: the model would fill in LATEST_VERSION for a missing field.
            val version = (json as? JsonObject)?.get("version")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asString
            return when (version) {
                ShizukuConfig.LATEST_VERSION.toString() -> GSON_IN.fromJson(json, ShizukuConfig::class.java)
                else -> {
                    LOGGER.w("config version %s is not the supported %d; starting empty", version, ShizukuConfig.LATEST_VERSION)
                    ShizukuConfig()
                }
            }
        }

        fun write(config: ShizukuConfig): Boolean {
            synchronized(ATOMIC_FILE) {
                val stream: FileOutputStream
                try {
                    stream = ATOMIC_FILE.startWrite()
                } catch (e: IOException) {
                    LOGGER.w("failed to write state: $e")
                    return false
                }

                try {
                    val json = GSON_OUT.toJson(config)
                    stream.write(json.toByteArray())

                    // Root and ADB starts share the same database; shell must own every replacement inode.
                    if (Process.myUid() == 0) Os.fchown(stream.fd, 2000, 2000)
                    Os.fchmod(stream.fd, 0x180 /* 0600 */)
                    ATOMIC_FILE.finishWrite(stream)
                    LOGGER.v("config saved")
                    return true
                } catch (tr: Throwable) {
                    LOGGER.w(tr, "can't save %s, restoring backup.", ATOMIC_FILE.baseFile)
                    ATOMIC_FILE.failWrite(stream)
                    return false
                }
            }
        }
    }
}
