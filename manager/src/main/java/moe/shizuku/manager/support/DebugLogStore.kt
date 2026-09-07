package moe.shizuku.manager.support

import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Access is serialized by DebugRecorder. */
internal class DebugLogStore(private val root: File) {
    data class Session(val id: String, val started: Long, val size: Long, val active: Boolean)
    private val marker get() = File(root, "active")
    fun activeId(): String? = marker.takeIf { it.isFile }?.readText()?.takeIf { validId(it) }
    fun directory(id: String): File {
        require(validId(id))
        return File(root, id)
    }
    fun create(now: Long = System.currentTimeMillis()): String {
        check(root.isDirectory || root.mkdirs())
        val id = "$now-${UUID.randomUUID()}"
        check(directory(id).mkdir())
        marker.writeText(id)
        prune()
        return id
    }
    fun finish() { check(!marker.exists() || marker.delete()) }
    fun sessions(): List<Session> {
        val active = activeId()
        return root.listFiles().orEmpty().filter { it.isDirectory && validId(it.name) }
            .map { Session(it.name, it.name.substringBefore('-').toLong(), it.walkTopDown().filter(File::isFile).sumOf(File::length), it.name == active) }
            .sortedByDescending { it.started }
    }
    fun prune() { sessions().filterNot { it.active }.drop(5).forEach { delete(it.id) } }
    fun delete(id: String) {
        check(id != activeId())
        check(directory(id).deleteRecursively())
    }
    fun export(id: String, cache: File): File {
        check(id != activeId())
        val source = directory(id)
        check(source.isDirectory)
        check(cache.isDirectory || cache.mkdirs())
        cache.listFiles().orEmpty().filter { System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60 * 1000L }.forEach(File::delete)
        val target = File(cache, "porter-$id.zip")
        cache.listFiles().orEmpty().filter { it != target }.sortedByDescending(File::lastModified).drop(4).forEach(File::delete)
        val temporary = File(cache, "porter-$id.tmp")
        try {
            ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
                source.listFiles().orEmpty().filter(File::isFile).sortedBy(File::getName).forEach { file ->
                    zip.putNextEntry(ZipEntry(file.name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            check(temporary.renameTo(target))
            return target
        } finally { temporary.delete() }
    }
    companion object {
        const val MAX_LOG_BYTES = 8L * 1024 * 1024
        private fun validId(id: String) = id.matches(Regex("[0-9]{1,19}-[0-9a-f-]{36}")) && id.substringBefore('-').toLongOrNull() != null
        fun appendBounded(input: InputStream, file: File, limit: Long = MAX_LOG_BYTES) {
            var remaining = (limit - file.length()).coerceAtLeast(0)
            java.io.FileOutputStream(file, true).use { out ->
                val buffer = ByteArray(8192)
                while (remaining > 0) {
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (count < 0) break
                    out.write(buffer, 0, count)
                    remaining -= count
                }
            }
        }
    }
}
