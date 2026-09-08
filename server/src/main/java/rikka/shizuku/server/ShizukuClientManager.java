package rikka.shizuku.server;

import java.util.ArrayList;
import java.util.List;
import moe.shizuku.server.IShizukuApplication;

public class ShizukuClientManager extends ClientManager<ShizukuConfigManager> {
    private final List<ClientRecord> attached = new ArrayList<>();

    public ShizukuClientManager(ShizukuConfigManager configManager) {
        super(configManager);
    }

    @Override
    public synchronized ClientRecord addClient(int uid, int pid, IShizukuApplication client, String packageName, int apiVersion) {
        ClientRecord record = super.addClient(uid, pid, client, packageName, apiVersion);
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
