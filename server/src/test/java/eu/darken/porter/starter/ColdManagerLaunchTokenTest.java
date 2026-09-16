package eu.darken.porter.starter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.FileDescriptor;
import java.util.concurrent.atomic.AtomicLong;

import eu.darken.porter.common.UserServiceLaunch;
import eu.darken.porter.protocol.PorterProtocol;

/**
 * A manager the platform just killed is being restarted by the launch itself, so its provider has no
 * server binder to hand over for the first moments after it comes up. The attach this validation runs
 * ahead of waits 5s for that; the validation has to wait too, or it rejects a live token.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class ColdManagerLaunchTokenTest {

    private static final String TOKEN = "e2fd2b5a-launch";
    private static final long TIMEOUT = 5000;
    private static final long READ_COST = 250;
    private static final long START = 10_000;

    /** Yields null until the manager has warmed up, then the server binder. Each read costs time. */
    private static class ColdManager implements ServiceStarter.BinderSource {

        private final IBinder binder;
        private final int readsWhileCold;
        private final AtomicLong clock;
        int reads;

        ColdManager(IBinder binder, int readsWhileCold, AtomicLong clock) {
            this.binder = binder;
            this.readsWhileCold = readsWhileCold;
            this.clock = clock;
        }

        @Override
        public IBinder read() {
            reads++;
            clock.addAndGet(READ_COST);
            return reads > readsWhileCold ? binder : null;
        }
    }

    /** Virtual time: a pause costs only the clock the loop measures its deadline against. */
    private static class TestPacer implements ServiceStarter.Pacer {

        private final AtomicLong clock;

        TestPacer(AtomicLong clock) {
            this.clock = clock;
        }

        @Override
        public long now() {
            return clock.get();
        }

        @Override
        public void pause(long millis) {
            clock.addAndGet(millis);
        }
    }

    /** Answers the launch-token query the way the running server does: this token is live. */
    private static class ServerBinder implements IBinder {

        @Override
        public boolean transact(int code, Parcel data, Parcel reply, int flags) {
            if (code != UserServiceLaunch.TRANSACTION) return false;
            data.setDataPosition(0);
            // Refuses the query the way the endpoint does when the token names another wire.
            data.enforceInterface(PorterProtocol.DESCRIPTOR);
            reply.writeNoException();
            reply.writeInt(1);
            reply.setDataPosition(0);
            return true;
        }

        @Override public String getInterfaceDescriptor() { return PorterProtocol.DESCRIPTOR; }
        @Override public boolean pingBinder() { return true; }
        @Override public boolean isBinderAlive() { return true; }
        @Override public android.os.IInterface queryLocalInterface(String descriptor) { return null; }
        @Override public void dump(FileDescriptor fd, String[] args) { }
        @Override public void dumpAsync(FileDescriptor fd, String[] args) { }
        @Override public void linkToDeath(DeathRecipient recipient, int flags) throws RemoteException { }
        @Override public boolean unlinkToDeath(DeathRecipient recipient, int flags) { return true; }
    }

    @Test
    public void aTokenIsStillValidatedWhenTheManagerIsStartingUp() {
        AtomicLong clock = new AtomicLong(START);
        ColdManager manager = new ColdManager(new ServerBinder(), 3, clock);

        boolean live = ServiceStarter.isLaunchTokenLive(TOKEN, manager, new TestPacer(clock));

        assertTrue("the manager handed over its binder on read 4, well inside the " + TIMEOUT
                + "ms the attach itself waits, but the starter gave up after " + manager.reads
                + " read(s) and rejected a live token", live);
    }

    @Test
    public void aManagerThatNeverWarmsUpIsGivenUpOnAtTheTimeout() {
        AtomicLong clock = new AtomicLong(START);
        ColdManager manager = new ColdManager(new ServerBinder(), Integer.MAX_VALUE, clock);

        assertFalse(ServiceStarter.isLaunchTokenLive(TOKEN, manager, new TestPacer(clock)));
        assertTrue("gave up without ever reading again, so nothing waits for a cold manager",
                manager.reads > 1);
        assertTrue("kept reading " + (clock.get() - START) + "ms past a " + TIMEOUT + "ms bound",
                clock.get() - START <= TIMEOUT + READ_COST);
    }
}
