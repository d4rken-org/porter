package eu.darken.porter.privileged

import eu.darken.porter.core.CallerIdentity
import eu.darken.porter.core.ClientCallback
import rikka.shizuku.server.ClientManager
import rikka.shizuku.server.ClientRecord

class ShizukuClientManager(configManager: ShizukuConfigManager) : ClientManager<ShizukuConfigManager>(configManager) {

    private val attached = ArrayList<ClientRecord>()

    @Synchronized
    override fun attach(identity: CallerIdentity, callback: ClientCallback, packageName: String, apiVersion: Int): ClientRecord? {
        val record = super.attach(identity, callback, packageName, apiVersion)
        if (record != null) {
            attached.removeIf { old -> findClient(old.uid, old.pid) !== old }
            attached.add(record)
        }
        return record
    }

    /**
     * A stored decision reaches a new process only while access is not paused, and only for the
     * installation it was made for.
     */
    override fun startsAllowed(identity: CallerIdentity, packageName: String): Boolean =
        !configManager.isAccessPaused &&
            super.startsAllowed(identity, packageName) &&
            configManager.verifiedForAttach(identity.uid, packageName)

    /** Told about every client whose process died; set once by the server that owns this manager. */
    @Volatile
    var onDeath: ((ClientRecord) -> Unit)? = null

    override fun onClientDied(record: ClientRecord) {
        onDeath?.invoke(record)
    }

    @Synchronized
    fun attachedClients(): List<ClientRecord> {
        attached.removeIf { record -> findClient(record.uid, record.pid) !== record }
        return ArrayList(attached)
    }
}
