package rikka.shizuku.server;

import android.content.pm.PackageInfo;
import android.os.Process;
import android.system.Os;
import android.util.AtomicFile;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class ConnectionHistory {
    private static final String TAG = "PorterConnections";
    private final AtomicFile file;
    private final Map<String, Record> records = new HashMap<>();
    private static final class Record {
        final int uid;
        final long installedAt;
        final long connectedAt;
        Record(int uid, long installedAt, long connectedAt) {
            this.uid = uid; this.installedAt = installedAt; this.connectedAt = connectedAt;
        }
        boolean matches(PackageInfo info) {
            return info.applicationInfo != null && uid == info.applicationInfo.uid && installedAt == info.firstInstallTime;
        }
    }

    ConnectionHistory(File path) {
        file = new AtomicFile(path);
        try {
            JSONArray items = new JSONArray(new String(file.readFully(), StandardCharsets.UTF_8));
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                records.put(item.getString("key"), new Record(item.getInt("uid"), item.getLong("installed"), item.getLong("connected")));
            }
        } catch (java.io.FileNotFoundException ignored) {
        } catch (Exception e) {
            records.clear();
            Log.w(TAG, "Cannot read connection history", e);
        }
    }

    private static String key(PackageInfo info) {
        return (info.applicationInfo.uid / 100000) + ":" + info.packageName;
    }

    synchronized long get(PackageInfo info) {
        Record record = records.get(key(info));
        return record != null && record.matches(info) ? record.connectedAt : 0;
    }

    synchronized void connected(PackageInfo info, long now) {
        if (info == null || info.applicationInfo == null || info.firstInstallTime <= 0 || info.firstInstallTime > now) return;
        Record previous = records.get(key(info));
        if (previous != null && previous.matches(info) && previous.connectedAt >= now) return;
        records.put(key(info), new Record(info.applicationInfo.uid, info.firstInstallTime, now));
        save();
    }

    synchronized void pruneUser(int userId, List<PackageInfo> installed, long snapshotStartedAt) {
        Map<String, PackageInfo> current = new HashMap<>();
        for (PackageInfo info : installed) {
            if (info.applicationInfo != null) current.put(key(info), info);
        }
        boolean changed = records.entrySet().removeIf(entry -> {
            if (entry.getValue().uid / 100000 != userId || entry.getValue().connectedAt > snapshotStartedAt) return false;
            PackageInfo info = current.get(entry.getKey());
            return info == null || !entry.getValue().matches(info);
        });
        if (changed) save();
    }

    private void save() {
        FileOutputStream stream = null;
        try {
            JSONArray items = new JSONArray();
            for (Map.Entry<String, Record> entry : records.entrySet()) {
                Record record = entry.getValue();
                items.put(new JSONObject().put("key", entry.getKey()).put("uid", record.uid)
                        .put("installed", record.installedAt).put("connected", record.connectedAt));
            }
            stream = file.startWrite();
            stream.write(items.toString().getBytes(StandardCharsets.UTF_8));
            if (Process.myUid() == 0) Os.fchown(stream.getFD(), 2000, 2000);
            Os.fchmod(stream.getFD(), 0600);
            file.finishWrite(stream);
        } catch (Exception e) {
            if (stream != null) file.failWrite(stream);
            Log.w(TAG, "Cannot save connection history", e);
        }
    }
}
