package eu.darken.porter.privileged;

import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import rikka.shizuku.server.util.Logger;

/**
 * Holds the debug-logging leases the manager takes out for the duration of a debug recording, and
 * keeps {@link Logger}'s gate at the furthest deadline any live lease asks for.
 *
 * <p>A lease rather than a flag, for two reasons. The manager app id matches across Android users
 * ({@link PorterServer#checkCallerManagerPermission}), so two users can record at once and one
 * of them stopping must not strip the detail out of the other's recording. And the gate must close
 * on its own: a manager that is killed mid-recording never asks for it to be turned off.
 *
 * <p>Death notification is the prompt path and the deadline is the backstop. Neither is sufficient
 * alone - a manager that merely stops recording does not die, and death delivery is asynchronous.
 */
final class DebugLogLeases {

    /**
     * Ceiling on a single grant. Deliberately larger than the recorder's own maximum recording
     * length so an ordinary request is granted in full, and deliberately not large: this bounds how
     * long the gate can stay open after everything else has failed.
     */
    static final long MAX_LEASE_MS = 60 * 60 * 1000L;

    private final Map<IBinder, Lease> leases = new HashMap<>();

    /**
     * Takes out, replaces or drops the lease held against {@code token}.
     *
     * @param durationMs how long the caller wants, clamped to {@link #MAX_LEASE_MS}; zero or less
     *                   releases.
     * @return the duration actually granted, or zero if nothing was granted.
     */
    synchronized long update(IBinder token, long durationMs) {
        if (token == null) return 0L;
        drop(token);
        long granted = 0L;
        if (durationMs > 0) {
            granted = Math.min(durationMs, MAX_LEASE_MS);
            Lease lease = new Lease(token, SystemClock.elapsedRealtime() + granted);
            try {
                token.linkToDeath(lease, 0);
                leases.put(token, lease);
            } catch (RemoteException e) {
                // The caller died between asking and being registered; grant it nothing.
                granted = 0L;
            }
        }
        apply();
        return granted;
    }

    /** Visible for tests. */
    synchronized int size() {
        return leases.size();
    }

    private void drop(IBinder token) {
        Lease existing = leases.remove(token);
        if (existing != null) unlink(existing);
    }

    /** Prunes what has expired or died and republishes the furthest remaining deadline. */
    private void apply() {
        long now = SystemClock.elapsedRealtime();
        long furthest = 0L;
        for (Iterator<Map.Entry<IBinder, Lease>> it = leases.entrySet().iterator(); it.hasNext(); ) {
            Lease lease = it.next().getValue();
            if (lease.until > now) {
                furthest = Math.max(furthest, lease.until);
                continue;
            }
            // Unregistered here and not merely forgotten: once the entry is gone, drop() can no
            // longer find it, so a manager that stays alive across several expiries would leave a
            // registration behind every time.
            it.remove();
            unlink(lease);
        }
        Logger.setDebugUntil(furthest);
    }

    private void unlink(Lease lease) {
        try {
            lease.token.unlinkToDeath(lease, 0);
        } catch (RuntimeException e) {
            // Already gone; the entry is what mattered.
        }
    }

    private final class Lease implements IBinder.DeathRecipient {
        private final IBinder token;
        private final long until;

        private Lease(IBinder token, long until) {
            this.token = token;
            this.until = until;
        }

        @Override
        public void binderDied() {
            synchronized (DebugLogLeases.this) {
                // Only if this lease is still the one on file: a replacement already unlinked us,
                // and dropping its entry here would revoke a lease that is still wanted.
                if (leases.get(token) == this) leases.remove(token);
                apply();
            }
        }
    }
}
