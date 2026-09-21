package eu.darken.porter.privileged

import android.os.IBinder
import android.os.RemoteException
import android.os.SystemClock
import rikka.shizuku.server.util.Logger

/**
 * Holds the debug-logging leases the manager takes out for the duration of a debug recording, and
 * keeps [Logger]'s gate at the furthest deadline any live lease asks for.
 *
 * A lease rather than a flag, for two reasons. A manager process that was restarted mid-recording
 * asks again with a new token, and the old token going away must not strip the detail out of the
 * recording that replaced it. And the gate must close on its own: a manager that is killed
 * mid-recording never asks for it to be turned off.
 *
 * Death notification is the prompt path and the deadline is the backstop. Neither is sufficient
 * alone - a manager that merely stops recording does not die, and death delivery is asynchronous.
 */
internal class DebugLogLeases {

    private val leases = HashMap<IBinder, Lease>()

    /**
     * Takes out, replaces or drops the lease held against [token].
     *
     * @param durationMs how long the caller wants, clamped to [MAX_LEASE_MS]; zero or less
     *                   releases.
     * @return the duration actually granted, or zero if nothing was granted.
     */
    @Synchronized
    fun update(token: IBinder?, durationMs: Long): Long {
        if (token == null) return 0L
        drop(token)
        var granted = 0L
        if (durationMs > 0) {
            granted = minOf(durationMs, MAX_LEASE_MS)
            val lease = Lease(token, SystemClock.elapsedRealtime() + granted)
            try {
                token.linkToDeath(lease, 0)
                leases[token] = lease
            } catch (e: RemoteException) {
                // The caller died between asking and being registered; grant it nothing.
                granted = 0L
            }
        }
        apply()
        return granted
    }

    /** Visible for tests. */
    @Synchronized
    fun size(): Int = leases.size

    private fun drop(token: IBinder) {
        val existing = leases.remove(token)
        if (existing != null) unlink(existing)
    }

    /** Prunes what has expired or died and republishes the furthest remaining deadline. */
    private fun apply() {
        val now = SystemClock.elapsedRealtime()
        var furthest = 0L
        val it = leases.entries.iterator()
        while (it.hasNext()) {
            val lease = it.next().value
            if (lease.until > now) {
                furthest = maxOf(furthest, lease.until)
                continue
            }
            // Unregistered here and not merely forgotten: once the entry is gone, drop() can no
            // longer find it, so a manager that stays alive across several expiries would leave a
            // registration behind every time.
            it.remove()
            unlink(lease)
        }
        Logger.setDebugUntil(furthest)
    }

    private fun unlink(lease: Lease) {
        try {
            lease.token.unlinkToDeath(lease, 0)
        } catch (e: RuntimeException) {
            // Already gone; the entry is what mattered.
        }
    }

    private inner class Lease(val token: IBinder, val until: Long) : IBinder.DeathRecipient {

        override fun binderDied() {
            synchronized(this@DebugLogLeases) {
                // Only if this lease is still the one on file: a replacement already unlinked us,
                // and dropping its entry here would revoke a lease that is still wanted.
                if (leases[token] === this) leases.remove(token)
                apply()
            }
        }
    }

    companion object {
        /**
         * Ceiling on a single grant. Deliberately larger than the recorder's own maximum recording
         * length so an ordinary request is granted in full, and deliberately not large: this bounds how
         * long the gate can stay open after everything else has failed.
         */
        const val MAX_LEASE_MS = 60 * 60 * 1000L
    }
}
