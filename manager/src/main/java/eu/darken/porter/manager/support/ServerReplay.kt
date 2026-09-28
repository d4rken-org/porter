package eu.darken.porter.manager.support

import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * Where a server stream starts: [since] is logcat's -T value, `1790607903.123` for
 * 2026-09-28T15:05:03.123Z, or null for everything logcat still retains for the PID.
 */
internal data class ServerReplay(val since: String?, val note: String? = null)

/**
 * A PID this recording already followed on this boot continues from the newest server.log write,
 * which also covers what it logged while no manager process was reading it, at the cost of a few
 * repeated lines. Any other PID replays from [recordingStart], so an instance that started during
 * the recording keeps its startup.
 *
 * [session] is the anchor of the manager process that began or resumed the recording, [attach] the
 * one taken for this attach. Never throws, because the follower may not.
 */
internal fun chooseServerReplay(
    pid: Int,
    recordingStart: Long,
    session: ClockAnchors.Reading,
    attach: ClockAnchors.Reading,
    directory: File,
): ServerReplay {
    // Every cutoff is wall time, and lines logged before the clock was set keep their old stamps,
    // so after a jump no cutoff separates this recording's lines from older ones.
    val moved = attach.wallMinusElapsedMs - session.wallMinusElapsedMs
    if (abs(moved) > CLOCK_TOLERANCE_MS) return ServerReplay(null, "Replay cutoff unreliable: wall clock moved ${moved}ms")
    val from = try {
        if (attachedBefore(pid, attach.bootCount, directory)) watermark(directory) ?: recordingStart else recordingStart
    } catch (e: Exception) {
        recordingStart
    }
    return ServerReplay(logcatTime(from))
}

/** The follower's attached event, `Server stream attached pid=42 boot=7 attach=1 replay=all` before its time. */
internal fun attachedNote(pid: Int, boot: Int, attach: Int, since: String?) =
    "Server stream attached pid=$pid boot=$boot attach=$attach replay=${since ?: "all"}"

/** A PID can be reused after a reboot, so only a known boot identifies the same instance. */
private fun attachedBefore(pid: Int, boot: Int, directory: File): Boolean {
    if (boot == -1) return false
    val events = File(directory, "events.txt").takeIf { it.isFile } ?: return false
    return events.useLines { lines ->
        lines.any { line ->
            ATTACHED.matchEntire(line)?.destructured?.let { (p, b) -> p.toIntOrNull() == pid && b.toIntOrNull() == boot } == true
        }
    }
}

private fun watermark(directory: File): Long? = listOf("server.log", "server.log.1")
    .map { File(directory, it) }
    .filter { it.isFile }
    .map { it.lastModified() }
    .filter { it > 0 }
    .maxOrNull()

internal fun logcatTime(epochMs: Long): String =
    String.format(Locale.ROOT, "%d.%03d", Math.floorDiv(epochMs, 1000L), Math.floorMod(epochMs, 1000L))

private const val CLOCK_TOLERANCE_MS = 1000L
private val ATTACHED = Regex("Server stream attached pid=(\\d+) boot=(-?\\d+) attach=\\d+ replay=\\S+ at \\d+")
