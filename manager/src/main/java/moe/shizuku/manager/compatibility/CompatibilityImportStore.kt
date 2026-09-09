package moe.shizuku.manager.compatibility

import android.util.AtomicFile
import eu.darken.porter.common.CompatibilitySetup
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal class CompatibilityImportStore(directory: File) {
    private val file = AtomicFile(File(directory, "compatibility-import.json"))
    val exists: Boolean get() = file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()

    fun save(decisions: JSONArray) {
        check(!exists) { "Discard the saved import before reviewing a new replacement" }
        val bytes = JSONObject().put("version", 1).put("decisions", decisions).toString().toByteArray(Charsets.UTF_8)
        check(bytes.size <= CompatibilitySetup.MAX_SNAPSHOT_BYTES) { "Access snapshot is too large" }
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }

    fun read(): String {
        val bytes = file.openRead().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(output.size() + count <= CompatibilitySetup.MAX_SNAPSHOT_BYTES) { "Access snapshot is too large" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val saved = JSONObject(bytes.toString(Charsets.UTF_8))
        check(saved.getInt("version") == 1) { "Unsupported saved import" }
        return saved.getJSONArray("decisions").toString()
    }

    fun discard() = file.delete()
}
