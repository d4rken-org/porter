package eu.darken.porter.common;

/** Porter manager-only setup operations; independent of the client SDK. */
public final class CompatibilitySetup {
    public static final int TRANSACTION = 10005;
    public static final int VERSION = 1;
    public static final int INSPECT = 0;
    public static final int PREVIEW_IMPORT = 1;
    public static final int APPLY_IMPORT = 2;
    public static final int REFRESH = 3;
    public static final int MAX_SNAPSHOT_BYTES = 256 * 1024;
    private CompatibilitySetup() {}
}
