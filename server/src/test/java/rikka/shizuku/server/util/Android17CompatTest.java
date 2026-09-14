package rikka.shizuku.server.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;

import android.content.pm.PackageInfo;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;

import rikka.hidden.compat.PackageManagerApis;

/**
 * The fallback was unreachable: both {@code NoThrow} helpers catch {@link Throwable}, so the
 * {@link NoSuchMethodError} the catch was written for never escaped them.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class Android17CompatTest {

    /** Stands in for IPackageManager; the fallback finds its method by name and prefix types. */
    public static final class FakePackageManager {
        PackageInfo answer;
        RuntimeException failure;
        String seenPackage;
        long seenFlags;
        int seenUserId;

        public PackageInfo getPackageInfo(String packageName, long flags, int userId) {
            seenPackage = packageName;
            seenFlags = flags;
            seenUserId = userId;
            if (failure != null) throw failure;
            return answer;
        }
    }

    private MockedStatic<PackageManagerApis> packages;
    private FakePackageManager fake;

    @Before
    public void setup() throws Exception {
        packages = Mockito.mockStatic(PackageManagerApis.class);
        fake = new FakePackageManager();
        set("sPackageManager", fake);
        set("sGetPackageInfoMethod", null);
    }

    @After
    public void teardown() throws Exception {
        packages.close();
        set("sPackageManager", null);
        set("sGetPackageInfoMethod", null);
    }

    private static void set(String name, Object value) throws Exception {
        Field field = Android17Compat.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private void directCallThrows(Throwable failure) {
        packages.when(() -> PackageManagerApis.getPackageInfo(anyString(), anyLong(), anyInt()))
                .thenThrow(failure);
    }

    @Test
    public void aMissingHiddenMethodReachesTheReflectionFallback() throws Exception {
        directCallThrows(new NoSuchMethodError("getPackageInfo"));
        fake.answer = new PackageInfo();

        assertSame(fake.answer, Android17Compat.getPackageInfoOrThrow("eu.darken.porter", 0x2040L, 10));
        assertArrayEquals(new Object[]{"eu.darken.porter", 0x2040L, 10},
                new Object[]{fake.seenPackage, fake.seenFlags, fake.seenUserId});
    }

    @Test
    public void aFallbackThatAlsoFailsIsNotReportedAsAMissingPackage() {
        NoSuchMethodError missing = new NoSuchMethodError("getPackageInfo");
        directCallThrows(missing);
        fake.failure = new IllegalStateException("package manager is unhappy");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> Android17Compat.getPackageInfoOrThrow("eu.darken.porter", 0, 0));
        assertTrue(failure.getSuppressed()[0] instanceof NoSuchMethodError);
    }

    @Test
    public void aParceledSecurityExceptionReturnsNullRatherThanPropagating() {
        // The server constructor and the process-observer callback both call this with no catch.
        directCallThrows(new SecurityException("Shell cannot query across users"));

        assertNull(Android17Compat.getPackageInfo("eu.darken.porter", 0, 0));
    }

    @Test
    public void theThrowingLookupStillDistinguishesAFailureFromAnAbsence() {
        directCallThrows(new SecurityException("Shell cannot query across users"));

        assertThrows(SecurityException.class,
                () -> Android17Compat.getPackageInfoOrThrow("eu.darken.porter", 0, 0));
    }
}
