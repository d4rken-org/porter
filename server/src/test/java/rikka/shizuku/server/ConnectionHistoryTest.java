package rikka.shizuku.server;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowProcess;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class ConnectionHistoryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test public void latestConnectionSurvivesRestartAndProfilesStaySeparate() {
        ShadowProcess.setUid(2000);
        File file = new File(temporary.getRoot(), "connections.json");
        var history = new ConnectionHistory(file);
        var owner = ApplicationDiscoveryTest.app("same.app", 10123);
        var profile = ApplicationDiscoveryTest.app("same.app", 1010123);
        assertEquals(0, history.get(owner));
        history.connected(owner, 1000);
        history.connected(profile, 2000);
        history.connected(owner, 3000);
        var reloaded = new ConnectionHistory(file);
        assertEquals(3000, reloaded.get(owner));
        assertEquals(2000, reloaded.get(profile));
    }
    @Test public void reinstallOrUidReuseDoesNotInheritConnectionTime() {
        ShadowProcess.setUid(2000);
        var history = new ConnectionHistory(new File(temporary.getRoot(), "connections.json"));
        var app = ApplicationDiscoveryTest.app("example", 10123);
        history.connected(app, 1000);
        app.firstInstallTime++;
        assertEquals(0, history.get(app));
        app.firstInstallTime--;
        app.applicationInfo.uid++;
        assertEquals(0, history.get(app));
    }
    @Test public void pruningOneSuccessfullyEnumeratedUserPreservesOtherProfiles() {
        ShadowProcess.setUid(2000);
        File file = new File(temporary.getRoot(), "connections.json");
        var history = new ConnectionHistory(file);
        var owner = ApplicationDiscoveryTest.app("owner", 10123);
        var profile = ApplicationDiscoveryTest.app("profile", 1010123);
        history.connected(owner, 1000);
        history.connected(profile, 2000);
        history.pruneUser(0, List.of(), 3000);
        var reloaded = new ConnectionHistory(file);
        assertEquals(0, reloaded.get(owner));
        assertEquals(2000, reloaded.get(profile));
    }
    @Test public void oldSnapshotCannotRemoveNewConnectionAndLateWritesCannotMoveTimeBackwards() {
        ShadowProcess.setUid(2000);
        var history = new ConnectionHistory(new File(temporary.getRoot(), "connections.json"));
        var app = ApplicationDiscoveryTest.app("example", 10123);
        history.connected(app, 3000);
        history.connected(app, 2000);
        history.pruneUser(0, List.of(), 2500);
        assertEquals(3000, history.get(app));
    }
    @Test public void brokenHistoryDoesNotAffectNewConnections() throws Exception {
        ShadowProcess.setUid(2000);
        File file = temporary.newFile("connections.json");
        Files.writeString(file.toPath(), "broken json");
        var history = new ConnectionHistory(file);
        var app = ApplicationDiscoveryTest.app("example", 10123);
        assertEquals(0, history.get(app));
        history.connected(app, 1234);
        assertEquals(1234, new ConnectionHistory(file).get(app));
    }
    @Test public void rootWritesRemainOwnedByShell() throws Exception {
        ShadowProcess.setUid(0);
        File file = new File(temporary.getRoot(), "connections.json");
        var history = new ConnectionHistory(file);
        try (var os = org.mockito.Mockito.mockStatic(android.system.Os.class)) {
            history.connected(ApplicationDiscoveryTest.app("example", 10123), 1234);
            os.verify(() -> android.system.Os.fchown(org.mockito.ArgumentMatchers.any(java.io.FileDescriptor.class),
                    org.mockito.ArgumentMatchers.eq(2000), org.mockito.ArgumentMatchers.eq(2000)));
            os.verify(() -> android.system.Os.fchmod(org.mockito.ArgumentMatchers.any(java.io.FileDescriptor.class), org.mockito.ArgumentMatchers.eq(0600)));
        }
        assertEquals(1234, new ConnectionHistory(file).get(ApplicationDiscoveryTest.app("example", 10123)));
    }

}
