package rikka.shizuku.server;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class LegacyAccessImportTest {
    private final LegacyAccessImport.App app = new LegacyAccessImport.App();
    private boolean existing;
    private final LegacyAccessImport.Resolver resolver = new LegacyAccessImport.Resolver() {
        public LegacyAccessImport.App resolve(String name) { return "example.client".equals(name) ? app : null; }
        public boolean hasPorterDecision(int uid) { return existing; }
    };
    public LegacyAccessImportTest() {
        app.packageName = "example.client";
        app.label = "Example";
        app.uid = 10123;
        app.certificate = "current-cert";
        app.exclusiveUid = true;
        app.legacy = true;
        app.granted = true;
    }
    private String entry(int uid, int flags) {
        return "{\"uid\":" + uid + ",\"flags\":" + flags + ",\"packages\":[\"example.client\"]}";
    }
    private String source(String entries) { return "{\"version\":2,\"packages\":[" + entries + "]}"; }

    @Test public void importCarriesIdentityAndDecision() throws Exception {
        List<LegacyAccessImport.Decision> decisions = LegacyAccessImport.preview(source(entry(10123, 2)), resolver);
        assertEquals(1, decisions.size());
        assertEquals("current-cert", decisions.get(0).certificate);
        assertTrue(LegacyAccessImport.canApply(LegacyAccessImport.decode(LegacyAccessImport.encode(decisions)).get(0), resolver));
    }
    @Test public void revokedLiveGrantIsNotResurrectedFromFile() throws Exception {
        app.granted = false;
        assertTrue(LegacyAccessImport.preview(source(entry(10123, 2)), resolver).isEmpty());
    }
    @Test public void explicitDenialCanBeReviewedButStaleDenialIsSkipped() throws Exception {
        assertTrue(LegacyAccessImport.preview(source(entry(10123, 4)), resolver).isEmpty());
        app.granted = false;
        assertEquals(4, LegacyAccessImport.preview(source(entry(10123, 4)), resolver).get(0).flags);
    }
    @Test public void existingPorterDecisionAlwaysWinsIncludingDefaultEntry() throws Exception {
        LegacyAccessImport.Decision decision = LegacyAccessImport.preview(source(entry(10123, 2)), resolver).get(0);
        existing = true;
        assertTrue(LegacyAccessImport.preview(source(entry(10123, 2)), resolver).isEmpty());
        assertFalse(LegacyAccessImport.canApply(decision, resolver));
    }
    @Test public void changedUidOrSignerAndSharedUidAreSkipped() throws Exception {
        LegacyAccessImport.Decision decision = LegacyAccessImport.preview(source(entry(10123, 2)), resolver).get(0);
        app.uid++;
        assertFalse(LegacyAccessImport.canApply(decision, resolver));
        app.uid--;
        app.certificate = "replacement-cert";
        assertFalse(LegacyAccessImport.canApply(decision, resolver));
        app.certificate = "current-cert";
        app.exclusiveUid = false;
        assertFalse(LegacyAccessImport.canApply(decision, resolver));
        assertTrue(LegacyAccessImport.preview(source(entry(10123, 2)), resolver).isEmpty());
    }
    @Test public void duplicateUidAndConflictingFlagsAreNotImported() throws Exception {
        assertTrue(LegacyAccessImport.preview(source(entry(10123, 2) + "," + entry(10123, 4)), resolver).isEmpty());
        assertTrue(LegacyAccessImport.preview(source(entry(10123, 6)), resolver).isEmpty());
    }
    @Test public void otherAndroidUsersAreNotImported() throws Exception {
        app.uid = 110123;
        assertTrue(LegacyAccessImport.preview(source(entry(110123, 2)), resolver).isEmpty());
    }
    @Test public void unsupportedAndMalformedSourcesFailBeforeReplacement() {
        assertThrows(Exception.class, () -> LegacyAccessImport.preview("{broken", resolver));
        assertThrows(IllegalArgumentException.class, () -> LegacyAccessImport.preview("{\"version\":3,\"packages\":[]}", resolver));
    }
}
