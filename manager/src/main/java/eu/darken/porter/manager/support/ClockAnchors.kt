package eu.darken.porter.manager.support

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Ties the timebases of a recording together. The events carry wall time, the service's
 * reconciler reports elapsed time, and threadtime logcat stamps carry neither a year nor a zone;
 * one anchor line holds all of them for the same moment, so each can be placed against the others.
 *
 * ```
 * Clock attach 1 wall=2026-09-28T17:05:03.123+0200 epochMs=1790607903123 elapsedMs=5000123 uptimeMs=4000456 bootCount=7
 * ```
 */
internal class ClockAnchors(private val clocks: Clocks) {
    interface Clocks {
        fun epochMs(): Long
        fun elapsedMs(): Long
        fun uptimeMs(): Long
        fun zone(): TimeZone
        /** -1 when the device does not report one. */
        fun bootCount(): Int
    }

    class DeviceClocks(private val context: Context) : Clocks {
        override fun epochMs() = System.currentTimeMillis()
        override fun elapsedMs() = SystemClock.elapsedRealtime()
        override fun uptimeMs() = SystemClock.uptimeMillis()
        override fun zone(): TimeZone = TimeZone.getDefault()
        override fun bootCount() = runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        }.getOrDefault(-1)
    }

    data class Reading(val epochMs: Long, val elapsedMs: Long, val uptimeMs: Long, val zone: TimeZone, val bootCount: Int) {
        /** The wall time at which this boot's elapsed clock read zero, while nobody sets the clock. */
        val wallMinusElapsedMs get() = epochMs - elapsedMs
    }

    constructor(context: Context) : this(DeviceClocks(context))

    /** The slower lookups go first, so the three clock readings sit as close together as possible. */
    fun read(): Reading {
        val zone = clocks.zone()
        val boot = clocks.bootCount()
        return Reading(clocks.epochMs(), clocks.elapsedMs(), clocks.uptimeMs(), zone, boot)
    }

    fun elapsedNow() = clocks.elapsedMs()

    fun bootCount() = clocks.bootCount()

    fun line(label: String, reading: Reading = read()) = with(reading) {
        "Clock $label wall=${format(WALL, this)} epochMs=$epochMs elapsedMs=$elapsedMs uptimeMs=$uptimeMs bootCount=$bootCount"
    }

    /** Never throws, because the follower may not. */
    fun append(events: File, label: String, reading: Reading? = null) {
        runCatching { events.appendText(line(label, reading ?: read()) + "\n") }
    }

    fun metadata(reading: Reading = read()) = with(reading) {
        "Time: $epochMs\nElapsed: $elapsedMs\nZone: ${zone.id} ${format("Z", this)}\nBoot count: $bootCount\n"
    }

    private fun format(pattern: String, reading: Reading) =
        SimpleDateFormat(pattern, Locale.ROOT).apply { timeZone = reading.zone }.format(Date(reading.epochMs))

    private companion object {
        const val WALL = "yyyy-MM-dd'T'HH:mm:ss.SSSZ"
    }
}
