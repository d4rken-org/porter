package eu.darken.porter.privileged

import android.content.pm.PackageInfo
import android.os.Process
import android.system.Os
import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal class ConnectionHistory(path: File) {

    private val file = AtomicFile(path)
    private val records = HashMap<String, Record>()

    private class Record(val uid: Int, val installedAt: Long, val connectedAt: Long) {

        fun matches(info: PackageInfo): Boolean {
            val applicationInfo = info.applicationInfo
            return applicationInfo != null && uid == applicationInfo.uid && installedAt == info.firstInstallTime
        }
    }

    init {
        try {
            val items = when (val stored = JSONTokener(String(file.readFully(), StandardCharsets.UTF_8)).nextValue()) {
                // The first format was the bare array.
                is JSONArray -> stored
                // get, not getInt: getInt would read 1.5 or a wrapped long as 1.
                is JSONObject -> when (val version = stored.get("version")) {
                    VERSION -> stored.getJSONArray("connections")
                    else -> {
                        Log.w(TAG, "Connection history version $version is not the supported $VERSION; starting empty")
                        JSONArray()
                    }
                }
                else -> throw IllegalArgumentException("Unexpected connection history: $stored")
            }
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                records[item.getString("key")] = Record(item.getInt("uid"), item.getLong("installed"), item.getLong("connected"))
            }
        } catch (ignored: FileNotFoundException) {
        } catch (e: Exception) {
            records.clear()
            Log.w(TAG, "Cannot read connection history", e)
        }
    }

    @Synchronized
    fun get(info: PackageInfo): Long {
        val record = records[key(info)]
        return if (record != null && record.matches(info)) record.connectedAt else 0
    }

    @Synchronized
    fun connected(info: PackageInfo?, now: Long) {
        if (info == null) return
        val applicationInfo = info.applicationInfo ?: return
        if (info.firstInstallTime <= 0 || info.firstInstallTime > now) return
        val previous = records[key(info)]
        if (previous != null && previous.matches(info) && previous.connectedAt >= now) return
        records[key(info)] = Record(applicationInfo.uid, info.firstInstallTime, now)
        save()
    }

    @Synchronized
    fun pruneUser(userId: Int, installed: List<PackageInfo>, snapshotStartedAt: Long) {
        val current = HashMap<String, PackageInfo>()
        for (info in installed) {
            if (info.applicationInfo != null) current[key(info)] = info
        }
        val changed = records.entries.removeIf { entry ->
            if (entry.value.uid / 100000 != userId || entry.value.connectedAt > snapshotStartedAt) return@removeIf false
            val info = current[entry.key]
            info == null || !entry.value.matches(info)
        }
        if (changed) save()
    }

    private fun save() {
        var stream: FileOutputStream? = null
        try {
            val items = JSONArray()
            for ((key, record) in records) {
                items.put(
                    JSONObject().put("key", key).put("uid", record.uid)
                        .put("installed", record.installedAt).put("connected", record.connectedAt),
                )
            }
            val envelope = JSONObject().put("version", VERSION).put("connections", items)
            stream = file.startWrite()
            stream.write(envelope.toString().toByteArray(StandardCharsets.UTF_8))
            if (Process.myUid() == 0) Os.fchown(stream.fd, 2000, 2000)
            Os.fchmod(stream.fd, 0x180 /* 0600 */)
            file.finishWrite(stream)
        } catch (e: Exception) {
            if (stream != null) file.failWrite(stream)
            Log.w(TAG, "Cannot save connection history", e)
        }
    }

    private companion object {
        const val TAG = "PorterConnections"
        const val VERSION = 1

        fun key(info: PackageInfo): String = (info.applicationInfo!!.uid / 100000).toString() + ":" + info.packageName
    }
}
