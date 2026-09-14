package rikka.shizuku.server.util;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;

import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * One package-manager lookup per {@code (package, user)}, answering three things rather than two:
 * the package is there, the package is not there, or the question was not answered.
 *
 * <p>{@code PackageManagerApis.getApplicationInfoNoThrow} catches {@link Throwable} and returns
 * null, so "uninstalled" and "the call failed" arrive as one observation. A timer that tears down a
 * privileged service on that reading kills it on the first transient failure.
 */
public final class PackageIdentity {

    private static final String DIGEST = "SHA-256";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    public enum State { PRESENT, ABSENT, LOOKUP_FAILED }

    /** What one user answered. Only produced for {@link State#PRESENT}. */
    public static final class Observed {
        public final int userId;
        public final int appId;
        /** One digest per signer, so a multi-signer package compares as a set. */
        public final Set<String> signerDigests;
        public final boolean multipleSigners;
        /** Null below API 28 and for multi-signer packages, where rotation is not possible. */
        public final Signature[] signingHistory;

        public Observed(int userId, int appId, Set<String> signerDigests, boolean multipleSigners, Signature[] signingHistory) {
            this.userId = userId;
            this.appId = appId;
            this.signerDigests = signerDigests;
            this.multipleSigners = multipleSigners;
            this.signingHistory = signingHistory;
        }

        @Override
        public String toString() {
            return "user=" + userId + " appId=" + appId + " signers=" + signerDigests;
        }
    }

    public static final class Result {
        public final State state;
        public final Observed observed;
        public final Throwable cause;

        private Result(State state, Observed observed, Throwable cause) {
            this.state = state;
            this.observed = observed;
            this.cause = cause;
        }

        public static Result present(Observed observed) {
            return new Result(State.PRESENT, observed, null);
        }

        public static Result absent() {
            return new Result(State.ABSENT, null, null);
        }

        public static Result failed(Throwable cause) {
            return new Result(State.LOOKUP_FAILED, null, cause);
        }
    }

    /**
     * What a record remembers about the installation it was created for: fixed at creation and never
     * updated. Both components are one per package name device-wide, which is what lets a record
     * shared across users be kept alive by any user holding a matching installation.
     */
    public static final class Identity {
        public final String packageName;
        public final int appId;
        public final Set<String> signerDigests;

        public Identity(String packageName, int appId, Set<String> signerDigests) {
            this.packageName = packageName;
            this.appId = appId;
            this.signerDigests = signerDigests;
        }

        /**
         * Whether {@code observed} is the same installation. A rotated signing key still matches
         * because the recorded certificate remains in the lineage; a different signing authority
         * does not, in any user.
         */
        public boolean matches(Observed observed) {
            if (observed == null || observed.appId != appId) return false;
            if (signerDigests.equals(observed.signerDigests)) return true;
            // Proof of rotation is an API 28 feature and only exists for a single signer. Below that,
            // and for multi-signer packages, the platform rejects an update whose key differs, so a
            // changed signature can only have come from an uninstall and a foreign reinstall.
            if (observed.multipleSigners || observed.signingHistory == null) return false;
            for (Signature past : observed.signingHistory) {
                if (signerDigests.contains(digestOf(past))) return true;
            }
            return false;
        }

        @Override
        public String toString() {
            return packageName + " appId=" + appId + " signers=" + signerDigests;
        }
    }

    private PackageIdentity() {}

    /**
     * {@code MATCH_UNINSTALLED_PACKAGES} is deliberate: without it a package hidden by {@code pm
     * hide} or by admin policy answers null and reads as an uninstall. With it, presence is decided
     * by the per-user installed bit, which a hidden package keeps and an {@code uninstall -k}
     * residue does not.
     */
    @SuppressWarnings("deprecation")
    public static long lookupFlags() {
        long flags = PackageManager.MATCH_UNINSTALLED_PACKAGES;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return flags | PackageManager.GET_SIGNING_CERTIFICATES;
        }
        return flags | PackageManager.GET_SIGNATURES;
    }

    public static Result of(String packageName, int userId) {
        PackageInfo packageInfo;
        try {
            // Through the compat layer, not around it: on the platform whose hidden signature
            // changed, going direct would report LOOKUP_FAILED forever while startup succeeded
            // through the reflection fallback, silently disabling cleanup.
            packageInfo = Android17Compat.getPackageInfoOrThrow(packageName, lookupFlags(), userId);
        } catch (Throwable tr) {
            return Result.failed(tr);
        }
        return classify(packageInfo, userId);
    }

    /** Turns an already-fetched {@link PackageInfo} into a verdict for one user. */
    public static Result classify(PackageInfo packageInfo, int userId) {
        // null for a missing package is the hidden IPackageManager contract; NameNotFoundException
        // is higher-level PackageManager behaviour and does not reach here.
        if (packageInfo == null) return Result.absent();
        ApplicationInfo applicationInfo = packageInfo.applicationInfo;
        if (applicationInfo == null) {
            return Result.failed(new IllegalStateException("no application info for " + packageInfo.packageName));
        }
        if ((applicationInfo.flags & ApplicationInfo.FLAG_INSTALLED) == 0) {
            return Result.absent();
        }
        Observed observed = observe(packageInfo, userId);
        if (observed == null) {
            // An answer that carries no signer is not evidence of a different signer.
            return Result.failed(new IllegalStateException("no signatures for " + packageInfo.packageName));
        }
        return Result.present(observed);
    }

    /** Reads an identity out of a {@link PackageInfo} already fetched with {@link #lookupFlags()}. */
    @SuppressWarnings("deprecation")
    public static Observed observe(PackageInfo packageInfo, int userId) {
        if (packageInfo == null || packageInfo.applicationInfo == null) return null;
        int appId = UserHandleCompat.getAppId(packageInfo.applicationInfo.uid);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            SigningInfo signingInfo = packageInfo.signingInfo;
            if (signingInfo != null) {
                // History is null for a multi-signer package, where rotation is not possible, so
                // there the whole signer set is the identity.
                boolean multiple = signingInfo.hasMultipleSigners();
                Set<String> digests = digestsOf(signingInfo.getApkContentsSigners());
                if (digests.isEmpty()) return null;
                return new Observed(userId, appId, digests, multiple,
                        multiple ? null : signingInfo.getSigningCertificateHistory());
            }
        }

        Set<String> digests = digestsOf(packageInfo.signatures);
        if (digests.isEmpty()) return null;
        return new Observed(userId, appId, digests, digests.size() > 1, null);
    }

    /** The identity a record remembers, taken from the same lookup that authorised the bind. */
    public static Identity identityOf(PackageInfo packageInfo) {
        Observed observed = observe(packageInfo, 0);
        Set<String> digests = observed != null ? observed.signerDigests : Collections.<String>emptySet();
        int appId = packageInfo.applicationInfo != null
                ? UserHandleCompat.getAppId(packageInfo.applicationInfo.uid) : -1;
        return new Identity(packageInfo.packageName, appId, digests);
    }

    private static Set<String> digestsOf(Signature[] signatures) {
        if (signatures == null || signatures.length == 0) return Collections.emptySet();
        Set<String> digests = new LinkedHashSet<>(signatures.length);
        for (Signature signature : signatures) {
            String digest = digestOf(signature);
            if (digest != null) digests.add(digest);
        }
        return Collections.unmodifiableSet(digests);
    }

    private static String digestOf(Signature signature) {
        if (signature == null) return null;
        try {
            byte[] hash = MessageDigest.getInstance(DIGEST).digest(signature.toByteArray());
            char[] out = new char[hash.length * 2];
            for (int i = 0; i < hash.length; i++) {
                out[i * 2] = HEX[(hash[i] >> 4) & 0xf];
                out[i * 2 + 1] = HEX[hash[i] & 0xf];
            }
            return new String(out);
        } catch (Throwable tr) {
            return null;
        }
    }

    /** Whether two users answered with the same installation, which they must for a stable scan. */
    public static boolean sameInstallation(Observed first, Observed second) {
        return first.appId == second.appId && first.signerDigests.equals(second.signerDigests);
    }
}
