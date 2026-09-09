package rikka.shizuku.server;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Bundle;
import java.io.FileInputStream;
import eu.darken.porter.common.CompatibilitySetup;
import rikka.hidden.compat.PackageManagerApis;
import rikka.hidden.compat.UserManagerApis;
import rikka.shizuku.server.util.Android17Compat;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class CompatibilitySetupHandler {
    interface Writer { void write(int uid, int flags) throws Exception; }
    private static final int PACKAGE_FLAGS = PackageManager.GET_PERMISSIONS
            | (android.os.Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES);
    private final ShizukuConfigManager config;
    private final Writer writer;
    private final Runnable refresh;

    CompatibilitySetupHandler(ShizukuConfigManager config, Writer writer, Runnable refresh) {
        this.config = config;
        this.writer = writer;
        this.refresh = refresh;
    }

    Bundle execute(int operation, String json) throws Exception {
        Bundle reply = new Bundle();
        reply.putInt("version", CompatibilitySetup.VERSION);
        if (operation == CompatibilitySetup.INSPECT) {
            java.util.Collection<Integer> users = UserManagerApis.getUserIdsNoThrow();
            if (users.isEmpty()) throw new IllegalStateException("Cannot check Android users");
            List<Integer> installed = new ArrayList<>();
            for (int user : users) {
                PackageInfo pi = Android17Compat.getPackageInfo(ServerConstants.COMPAT_APPLICATION_ID, PACKAGE_FLAGS, user);
                if (pi != null && pi.applicationInfo != null
                        && (pi.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_INSTALLED) != 0) installed.add(user);
            }
            reply.putIntArray("users", installed.stream().mapToInt(Integer::intValue).toArray());
            PackageInfo pi = Android17Compat.getPackageInfo(ServerConstants.COMPAT_APPLICATION_ID, PACKAGE_FLAGS, 0);
            reply.putString("certificate", pi == null ? null : certificate(pi));
            reply.putBoolean("available", Compatibility.isAvailable());
        } else if (operation == CompatibilitySetup.PREVIEW_IMPORT) {
            PackageInfo original = Android17Compat.getPackageInfo(ServerConstants.COMPAT_APPLICATION_ID, PACKAGE_FLAGS, 0);
            if (original == null || Compatibility.isAvailable() || original.permissions == null)
                throw new IllegalStateException("Original Shizuku is unavailable");
            boolean definesLegacy = false;
            for (android.content.pm.PermissionInfo permission : original.permissions) {
                if (ServerConstants.LEGACY_PERMISSION.equals(permission.name)
                        && ServerConstants.COMPAT_APPLICATION_ID.equals(permission.packageName)
                        && (permission.protectionLevel & android.content.pm.PermissionInfo.PROTECTION_MASK_BASE) == android.content.pm.PermissionInfo.PROTECTION_DANGEROUS)
                    definesLegacy = true;
            }
            if (!definesLegacy) throw new IllegalStateException("Installed app does not own the Shizuku permission");
            File source = new File("/data/user_de/0/com.android.shell/shizuku.json");
            File backup = new File(source.getPath() + ".bak");
            try (InputStream in = new FileInputStream(backup.exists() ? backup : source)) {
                reply.putString("decisions", LegacyAccessImport.encode(LegacyAccessImport.preview(readBounded(in), resolver)));
            }
        } else if (operation == CompatibilitySetup.APPLY_IMPORT) {
            if (!Compatibility.isAvailable()) throw new IllegalStateException("Compatibility support is not verified");
            if (json == null || json.getBytes(StandardCharsets.UTF_8).length > CompatibilitySetup.MAX_SNAPSHOT_BYTES)
                throw new IllegalArgumentException("Invalid access snapshot");
            int applied = 0, skipped = 0;
            for (LegacyAccessImport.Decision decision : LegacyAccessImport.decode(json)) {
                if (!LegacyAccessImport.canApply(decision, resolver)) { skipped++; continue; }
                writer.write(decision.uid, decision.flags);
                applied++;
            }
            config.persistImport();
            reply.putInt("applied", applied);
            reply.putInt("skipped", skipped);
            refresh.run();
        } else if (operation == CompatibilitySetup.REFRESH) {
            if (!Compatibility.isAvailable()) throw new IllegalStateException("Compatibility support is not verified");
            refresh.run();
        } else throw new IllegalArgumentException("Unknown compatibility operation");
        return reply;
    }

    private final LegacyAccessImport.Resolver resolver = new LegacyAccessImport.Resolver() {
        @Override public boolean hasPorterDecision(int uid) { return config.find(uid) != null; }
        @Override public LegacyAccessImport.App resolve(String name) throws Exception {
            PackageInfo pi = Android17Compat.getPackageInfo(name, PACKAGE_FLAGS, 0);
            if (pi == null || pi.applicationInfo == null) return null;
            LegacyAccessImport.App app = new LegacyAccessImport.App();
            app.uid = pi.applicationInfo.uid;
            app.packageName = name;
            app.label = name;
            app.certificate = certificate(pi);
            List<String> names = PackageManagerApis.getPackagesForUidNoThrow(app.uid);
            app.exclusiveUid = names.size() == 1 && names.contains(name);
            app.legacy = ClientRouting.requests(pi.requestedPermissions, ServerConstants.LEGACY_PERMISSION);
            app.granted = Android17Compat.checkPermission(ServerConstants.LEGACY_PERMISSION, name, 0) == PackageManager.PERMISSION_GRANTED;
            return app;
        }
    };

    private static String certificate(PackageInfo info) throws Exception {
        Signature[] signatures = Compatibility.signatures(info);
        if (signatures == null || signatures.length == 0) throw new IllegalStateException("Missing signing certificate");
        List<String> hashes = new ArrayList<>();
        for (Signature signature : signatures) {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray());
            StringBuilder value = new StringBuilder();
            for (byte b : digest) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
            hashes.add(value.toString());
        }
        Collections.sort(hashes);
        return android.text.TextUtils.join(",", hashes);
    }

    private static String readBounded(InputStream input) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (bytes.size() + read > CompatibilitySetup.MAX_SNAPSHOT_BYTES) throw new IllegalArgumentException("Access database is too large");
            bytes.write(buffer, 0, read);
        }
        return bytes.toString("UTF-8");
    }
}
