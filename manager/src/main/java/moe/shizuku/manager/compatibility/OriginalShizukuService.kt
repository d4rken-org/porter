package moe.shizuku.manager.compatibility

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal class OriginalShizukuService(
    private val command: suspend (Array<String>) -> String = { CompatibilityService.command(it) },
    private val pause: suspend () -> Unit = { delay(200) },
    private val legacyPs: Boolean = false,
) {
    private data class Process(val pid: Int, val uid: Int)

    private suspend fun running(): List<Process> {
        val args = if (legacyPs) arrayOf("ps", "-n") else arrayOf("ps", "-A", "-o", "PID,UID,NAME")
        val lines = command(args).trim().lines()
        val header = lines.firstOrNull()?.trim()?.split(Regex("\\s+"))
        val expected = if (legacyPs) listOf("USER", "PID", "PPID", "VSIZE", "RSS", "WCHAN", "PC", "NAME")
            else listOf("PID", "UID", "NAME")
        check(header == expected) { "Could not check the Shizuku service" }
        return lines.drop(1).mapNotNull { line ->
            val fields = line.trim().split(Regex("\\s+"))
            if (fields.lastOrNull() != "shizuku_server" || (if (legacyPs) fields.size < 8 else fields.size != 3)) return@mapNotNull null
            val pid = fields[if (legacyPs) 1 else 0].toIntOrNull() ?: return@mapNotNull null
            val uid = fields[if (legacyPs) 0 else 1].toIntOrNull() ?: return@mapNotNull null
            if (pid <= 1 || uid !in listOf(0, 2000)) return@mapNotNull null
            Process(pid, uid)
        }
    }

    private suspend fun identity(process: Process): Long? {
        try {
            val name = command(arrayOf("cat", "/proc/${process.pid}/cmdline")).substringBefore('\u0000')
            if (name.isEmpty()) return null
            check(name == "shizuku_server") { "The Shizuku service changed; try again" }
            val stat = command(arrayOf("cat", "/proc/${process.pid}/stat"))
            return stat.substringAfterLast(") ", "").trim().split(Regex("\\s+"))
                .getOrNull(19)?.toLongOrNull() ?: error("Could not identify the Shizuku service")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (process !in running()) return null
            throw e
        }
    }

    suspend fun stop() {
        for (process in running()) {
            val started = identity(process) ?: continue
            val current = identity(process) ?: continue
            check(current == started) { "The Shizuku service changed; try again" }
            try { command(arrayOf("kill", "-TERM", process.pid.toString())) }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                if (identity(process) == started) throw e
            }
        }
        repeat(25) {
            if (running().none { identity(it) != null }) return
            pause()
        }
        error("Shizuku is still running")
    }
}
