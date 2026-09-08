package eu.darken.porter.common;

/** Manager-only global access control; independent of the public Shizuku API. */
public final class GlobalAccess {
    public static final int TRANSACTION = 10004;
    public static final int VERSION = 1;
    public static final int READ = 0;
    public static final int WRITE = 1;
    private GlobalAccess() {}
}
