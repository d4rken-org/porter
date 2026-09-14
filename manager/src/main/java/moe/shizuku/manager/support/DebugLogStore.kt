package moe.shizuku.manager.support

import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Access is serialized by DebugRecorder. */
internal open class DebugLogStore(private val root: File) {
    data class Session(val id: String, val started: Long, val size: Long, val active: Boolean)
    private val marker get() = File(root, "active")
    open fun activeId(): String? = marker.takeIf { it.isFile }?.readText()?.takeIf { validId(it) }
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
    open fun prune() { sessions().filterNot { it.active }.drop(5).forEach { delete(it.id) } }
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
        /**
         * Appends [input] to [file] until EOF, keeping the newest bytes rather than the oldest: at
         * half of [limit] the file is rotated onto "<name>.1" and a fresh one started, so the pair
         * never exceeds [limit] and, past the first rotation, never holds less than half of it.
         */
        fun appendRotating(input: InputStream, file: File, limit: Long = MAX_LOG_BYTES) {
            val segment = (limit / 2).coerceAtLeast(1)
            val previous = File(file.parentFile, "${file.name}.1")
            val buffer = ByteArray(8192)
            var written = file.length()
            if (written > segment) written = trimToTail(file, buffer, segment)
            var out = java.io.FileOutputStream(file, true)
            try {
                while (true) {
                    // A full segment still reads a segment's worth, because the rotation below is
                    // what makes room for it. Rotating only once there are bytes to write keeps the
                    // older segment through an idle stream and through EOF.
                    val room = if (written >= segment) segment else segment - written
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), room).toInt())
                    if (count < 0) break
                    if (written >= segment) {
                        out.close()
                        previous.delete()
                        file.renameTo(previous)
                        // Not appending: a rename leaves no file behind, and a failed one must
                        // still be cleared or the limit stops holding.
                        out = java.io.FileOutputStream(file)
                        written = 0
                    }
                    out.write(buffer, 0, count)
                    written += count
                }
            } finally { runCatching { out.close() } }
        }
        /**
         * Reduces [file] to its last [keep] bytes and returns the length it now has. A log left by
         * a build that did not rotate can be a whole [MAX_LOG_BYTES] by itself, and rotating that
         * wholesale would carry it into "<name>.1" and put the pair over the limit.
         */
        private fun trimToTail(file: File, buffer: ByteArray, keep: Long): Long {
            val tail = File(file.parentFile, "${file.name}.tail")
            val copied = runCatching {
                java.io.RandomAccessFile(file, "r").use { source ->
                    source.seek(source.length() - keep)
                    java.io.FileOutputStream(tail).use { out ->
                        while (true) {
                            val count = source.read(buffer)
                            if (count < 0) break
                            out.write(buffer, 0, count)
                        }
                    }
                }
            }.isSuccess
            // Appending to an untrimmed log beats dropping the recording, so a failure here only
            // costs the limit until the next rotation.
            if (copied && tail.renameTo(file)) return keep
            tail.delete()
            return file.length()
        }
        /** Bounded like [appendBounded], but keeps reading to EOF so the caller can join on it. */
        fun drainBounded(input: InputStream, file: File, limit: Long = MAX_LOG_BYTES) {
            appendBounded(input, file, limit)
            val buffer = ByteArray(8192)
            while (input.read(buffer) >= 0) Unit
        }
    }
}
