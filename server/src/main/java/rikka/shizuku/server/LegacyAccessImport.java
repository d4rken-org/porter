package rikka.shizuku.server;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class LegacyAccessImport {
    static final int ALLOW = 2;
    static final int DENY = 4;
    static final int MAX_ENTRIES = 500;
    private static final Gson GSON = new Gson();

    interface Resolver {
        App resolve(String packageName) throws Exception;
        boolean hasPorterDecision(int uid);
    }

    static final class App {
        String packageName;
        String label;
        String certificate;
        int uid;
        boolean granted;
        boolean exclusiveUid;
        boolean legacy;
    }

    @androidx.annotation.Keep
    static final class Decision {
        String packageName;
        String label;
        String certificate;
        int uid;
        int flags;
    }

    @androidx.annotation.Keep
    static final class Source {
        int version;
        List<Entry> packages;
    }

    @androidx.annotation.Keep
    static final class Entry {
        int uid;
        int flags;
        List<String> packages;
    }

    static List<Decision> preview(String json, Resolver resolver) throws Exception {
        Source source = GSON.fromJson(json, Source.class);
        if (source == null || source.version != 2 || source.packages == null || source.packages.size() > MAX_ENTRIES)
            throw new IllegalArgumentException("Unsupported Shizuku access database");
        List<Decision> result = new ArrayList<>();
        Set<Integer> duplicates = new HashSet<>();
        Set<Integer> seen = new HashSet<>();
        for (Entry entry : source.packages) {
            if (entry != null && !seen.add(entry.uid)) duplicates.add(entry.uid);
        }
        for (Entry entry : source.packages) {
            if (entry == null || entry.uid < 10000 || entry.uid >= 100000 || duplicates.contains(entry.uid)
                    || entry.packages == null || entry.packages.size() != 1
                    || (entry.flags != ALLOW && entry.flags != DENY) || resolver.hasPorterDecision(entry.uid)) continue;
            String name = entry.packages.get(0);
            if (!clientPackage(name)) continue;
            App app = resolver.resolve(name);
            if (app == null || app.uid != entry.uid || !app.exclusiveUid || !app.legacy
                    || app.certificate == null || (entry.flags == ALLOW) != app.granted) continue;
            Decision decision = new Decision();
            decision.packageName = name;
            decision.label = app.label;
            decision.certificate = app.certificate;
            decision.uid = app.uid;
            decision.flags = entry.flags;
            result.add(decision);
        }
        return result;
    }

    static List<Decision> decode(String json) {
        Decision[] decisions = GSON.fromJson(json, Decision[].class);
        if (decisions == null || decisions.length > MAX_ENTRIES) throw new IllegalArgumentException("Invalid access snapshot");
        return java.util.Arrays.asList(decisions);
    }

    static boolean canApply(Decision decision, Resolver resolver) throws Exception {
        if (decision == null || !clientPackage(decision.packageName) || decision.uid < 10000 || decision.uid >= 100000
                || decision.certificate == null || (decision.flags != ALLOW && decision.flags != DENY)
                || resolver.hasPorterDecision(decision.uid)) return false;
        App app = resolver.resolve(decision.packageName);
        return app != null && app.uid == decision.uid && app.exclusiveUid && app.legacy
                && decision.certificate.equals(app.certificate);
    }

    static String encode(List<Decision> decisions) { return GSON.toJson(decisions); }

    private static boolean clientPackage(String name) {
        return name != null && !name.isEmpty() && !name.equals("eu.darken.porter")
                && !name.equals("moe.shizuku.privileged.api");
    }
}
