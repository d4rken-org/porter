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
            if (configManager.isAccessPaused) record.allowed = false
            attached.removeIf { old -> findClient(old.uid, old.pid) !== old }
            attached.add(record)
        }
        return record
    }

    @Synchronized
    fun attachedClients(): List<ClientRecord> {
        attached.removeIf { record -> findClient(record.uid, record.pid) !== record }
        return ArrayList(attached)
    }
}
