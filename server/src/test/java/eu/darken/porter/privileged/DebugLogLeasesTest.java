package eu.darken.porter.privileged;

import android.os.IBinder;
import android.os.RemoteException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;

import java.io.FileDescriptor;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import rikka.shizuku.server.util.Logger;

import static org.junit.Assert.*;

/**
 * The gate must close on its own and must survive two managers recording at once, so these exercise
 * the deadline and the lease bookkeeping rather than the logging itself.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class DebugLogLeasesTest {

    private static final long MINUTE = 60 * 1000L;

    /** A remote-like token: a real local Binder's linkToDeath is a no-op, so death is unobservable. */
    static class FakeToken implements IBinder {
        final List<DeathRecipient> recipients = new ArrayList<>();

        @Override public void linkToDeath(DeathRecipient recipient, int flags) throws RemoteException {
            recipients.add(recipient);
        }
        @Override public boolean unlinkToDeath(DeathRecipient recipient, int flags) {
            return recipients.remove(recipient);
        }
        void die() {
            for (DeathRecipient recipient : new ArrayList<>(recipients)) recipient.binderDied();
        }

        @Override public String getInterfaceDescriptor() { return "fake"; }
        @Override public boolean pingBinder() { return true; }
        @Override public boolean isBinderAlive() { return true; }
        @Override public android.os.IInterface queryLocalInterface(String descriptor) { return null; }
        @Override public void dump(FileDescriptor fd, String[] args) { }
        @Override public void dumpAsync(FileDescriptor fd, String[] args) { }
        @Override public boolean transact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) {
            return false;
        }
    }

    private DebugLogLeases leases;

    @Before public void closeTheGate() {
        Logger.setDebugUntil(0L);
        Logger.setDebugAlways(false);
        leases = new DebugLogLeases();
    }

    @Test public void aGrantOpensTheGateAndTheCeilingBoundsIt() {
        assertEquals(DebugLogLeases.MAX_LEASE_MS, leases.update(new FakeToken(), 5 * DebugLogLeases.MAX_LEASE_MS));
        assertTrue(Logger.debugEnabled());
    }

    @Test public void theGateClosesWhenTheGrantRunsOutWithNothingElseHappening() {
        leases.update(new FakeToken(), 10 * MINUTE);
        ShadowSystemClock.advanceBy(Duration.ofMinutes(9));
        assertTrue(Logger.debugEnabled());
        ShadowSystemClock.advanceBy(Duration.ofMinutes(2));
        assertFalse(Logger.debugEnabled());
    }

    @Test public void oneManagerStoppingLeavesTheOtherRecordingCovered() {
        FakeToken first = new FakeToken();
        FakeToken second = new FakeToken();
        leases.update(first, 5 * MINUTE);
        leases.update(second, 30 * MINUTE);
        leases.update(first, 0);
        ShadowSystemClock.advanceBy(Duration.ofMinutes(20));
        assertTrue(Logger.debugEnabled());
        assertEquals(1, leases.size());
    }

    @Test public void releasingTheLastLeaseClosesTheGate() {
        FakeToken token = new FakeToken();
        leases.update(token, 10 * MINUTE);
        assertEquals(0, leases.update(token, 0));
        assertFalse(Logger.debugEnabled());
        assertEquals(0, leases.size());
    }

    @Test public void aDeadManagerReleasesItsOwnLease() {
        FakeToken token = new FakeToken();
        leases.update(token, 10 * MINUTE);
        token.die();
        assertFalse(Logger.debugEnabled());
        assertEquals(0, leases.size());
    }

    @Test public void aReplacedLeaseDyingLateDoesNotRevokeTheLiveOne() {
        FakeToken token = new FakeToken();
        leases.update(token, 5 * MINUTE);
        IBinder.DeathRecipient stale = token.recipients.get(0);
        leases.update(token, 30 * MINUTE);
        stale.binderDied();
        ShadowSystemClock.advanceBy(Duration.ofMinutes(20));
        assertTrue(Logger.debugEnabled());
    }

    @Test public void anExpiredLeaseIsPrunedAndUnregistered() {
        FakeToken expiring = new FakeToken();
        leases.update(expiring, 5 * MINUTE);
        ShadowSystemClock.advanceBy(Duration.ofMinutes(6));
        leases.update(new FakeToken(), 5 * MINUTE);
        assertEquals(1, leases.size());
        // Forgetting the entry without unlinking would leak a registration per expiry, and drop()
        // could never clean it up afterwards because the entry is already gone.
        assertTrue(expiring.recipients.isEmpty());
    }

    @Test public void aNullTokenGrantsNothing() {
        assertEquals(0, leases.update(null, 10 * MINUTE));
        assertFalse(Logger.debugEnabled());
    }

    @Test public void aManagerThatDiedBeforeRegistrationGrantsNothing() {
        FakeToken dead = new FakeToken() {
            @Override public void linkToDeath(DeathRecipient recipient, int flags) throws RemoteException {
                throw new RemoteException("already gone");
            }
        };
        assertEquals(0, leases.update(dead, 10 * MINUTE));
        assertFalse(Logger.debugEnabled());
        assertEquals(0, leases.size());
    }

    @Test public void anAlwaysOnBuildSurvivesALeaseBeingTakenAndDropped() {
        Logger.setDebugAlways(true);
        FakeToken token = new FakeToken();
        leases.update(token, 10 * MINUTE);
        leases.update(token, 0);
        // The lease republishes the deadline as it comes and goes; a debug build must not lose its
        // logging the first time someone records.
        assertTrue(Logger.debugEnabled());
    }

    @Test public void infoAndAboveNeverDependOnTheGate() {
        Logger logger = new Logger("test");
        assertFalse(Logger.debugEnabled());
        assertTrue(logger.isLoggable("test", android.util.Log.INFO));
        assertTrue(logger.isLoggable("test", android.util.Log.WARN));
        assertTrue(logger.isLoggable("test", android.util.Log.ERROR));
        assertFalse(logger.isLoggable("test", android.util.Log.DEBUG));
        assertFalse(logger.isLoggable("test", android.util.Log.VERBOSE));
    }

    @Test public void debugAndVerboseFollowTheGate() {
        Logger logger = new Logger("test");
        leases.update(new FakeToken(), 10 * MINUTE);
        assertTrue(logger.isLoggable("test", android.util.Log.DEBUG));
        assertTrue(logger.isLoggable("test", android.util.Log.VERBOSE));
    }
}
