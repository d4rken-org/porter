package eu.darken.porter.common;

/**
 * Porter-only launch validation for user services; independent of the Shizuku API.
 *
 * <p>Lives here rather than in {@code :server} because the starter asks the question and
 * {@code :server} already depends on {@code :starter}.
 */
public final class UserServiceLaunch {
    public static final int TRANSACTION = 10007;
    private UserServiceLaunch() {}
}
