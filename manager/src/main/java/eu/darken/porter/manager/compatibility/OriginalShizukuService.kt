package eu.darken.porter.manager.compatibility

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal class OriginalShizukuService(
    private val command: suspend (Array<String>) -> String = { CompatibilityService.command(it) },
    private val pause: suspend () -> Unit = { delay(200) },
    private val legacyPs: Boolean = false,
) {
    private data class Process(val pid: Int, val uid: Int)
    private data class Row(val process: Process, val parent: Int, val name: String)

    private suspend fun table(): List<Row> {
        val args = if (legacyPs) arrayOf("ps", "-n") else arrayOf("ps", "-A", "-o", "PID,PPID,UID,NAME")
        val lines = command(args).trim().lines()
        val header = lines.firstOrNull()?.trim()?.split(Regex("\\s+"))
        val expected = if (legacyPs) listOf("USER", "PID", "PPID", "VSIZE", "RSS", "WCHAN", "PC", "NAME")
            else listOf("PID", "PPID", "UID", "NAME")
        check(header == expected) { "Could not check the Shizuku service" }
        return lines.drop(1).mapNotNull { line ->
            val fields = line.trim().split(Regex("\\s+"))
            if (if (legacyPs) fields.size < 8 else fields.size != 4) return@mapNotNull null
            val pid = fields[if (legacyPs) 1 else 0].toIntOrNull() ?: return@mapNotNull null
            val parent = fields[if (legacyPs) 2 else 1].toIntOrNull() ?: return@mapNotNull null
            val uid = fields[if (legacyPs) 0 else 2].toIntOrNull() ?: return@mapNotNull null
            if (pid <= 1 || uid !in listOf(0, 2000)) return@mapNotNull null
            Row(Process(pid, uid), parent, fields.last())
        }
    }

    private suspend fun running() = table().filter { it.name == SERVER }

    private suspend fun <T : Any> whileListed(row: Row, read: suspend () -> T?): T? = try {
        read()
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        if (table().none { it.process == row.process && it.name == row.name }) null else throw e
    }

    private suspend fun cmdline(row: Row): String? = whileListed(row) {
        command(arrayOf("cat", "/proc/${row.process.pid}/cmdline")).substringBefore('\u0000').ifEmpty { null }
    }

    private suspend fun identity(row: Row, named: (String) -> Boolean = { it == SERVER }): Long? {
        val name = cmdline(row) ?: return null
        return whileListed(row) {
            check(named(name)) { "The Shizuku service changed; try again" }
            val stat = command(arrayOf("cat", "/proc/${row.process.pid}/stat"))
            stat.substringAfterLast(") ", "").trim().split(Regex("\\s+"))
                .getOrNull(19)?.toLongOrNull() ?: error("Could not identify the Shizuku service")
        }
    }

    private suspend fun verified(row: Row, named: (String) -> Boolean = { it == SERVER }): Long? {
        val started = identity(row, named) ?: return null
        val current = identity(row, named) ?: return null
        check(current == started) { "The Shizuku service changed; try again" }
        return started
    }

    private suspend fun signal(row: Row, started: Long, named: (String) -> Boolean = { it == SERVER }) {
        try { command(arrayOf("kill", "-TERM", row.process.pid.toString())) }
        catch (e: Exception) {
            if (e is CancellationException) throw e
            if (identity(row, named) == started) throw e
        }
    }

    // Some forks keep their starter alive as the server's parent, restarting the server when it dies.
    private fun isStarter(name: String) = name.endsWith("/libshizuku.so")

    suspend fun stop() {
        val table = table()
        val starters = mutableMapOf<Row, Long>()
        for (server in table.filter { it.name == SERVER }) {
            val started = verified(server) ?: continue
            val parent = table.firstOrNull { it.process.pid == server.parent }
            if (parent != null && starters.keys.none { it.process == parent.process } && cmdline(parent)?.let(::isStarter) == true) {
                verified(parent, ::isStarter)?.let { starter ->
                    signal(parent, starter, ::isStarter)
                    starters[parent] = starter
                }
            }
            signal(server, started)
        }
        repeat(25) {
            if (running().none { identity(it) != null } && starters.none { (row, started) -> identity(row, ::isStarter) == started }) return
            pause()
        }
        error("Shizuku is still running")
    }

    private companion object {
        const val SERVER = "shizuku_server"
    }
}
