package rikka.shizuku.server;

import java.util.ArrayList;
import java.util.List;

import eu.darken.porter.core.CallerIdentity;
import eu.darken.porter.core.ClientCallback;

public class ShizukuClientManager extends ClientManager<ShizukuConfigManager> {
    private final List<ClientRecord> attached = new ArrayList<>();

    public ShizukuClientManager(ShizukuConfigManager configManager) {
        super(configManager);
    }

    @Override
    public synchronized ClientRecord attach(CallerIdentity identity, ClientCallback callback, String packageName, int apiVersion) {
        ClientRecord record = super.attach(identity, callback, packageName, apiVersion);
        if (record != null) {
            if (getConfigManager().isAccessPaused()) record.allowed = false;
            attached.removeIf(old -> findClient(old.uid, old.pid) != old);
            attached.add(record);
        }
        return record;
    }

    public synchronized List<ClientRecord> attachedClients() {
        attached.removeIf(record -> findClient(record.uid, record.pid) != record);
        return new ArrayList<>(attached);
    }
}
