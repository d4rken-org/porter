package eu.darken.porter.manager.shell

import android.os.Handler
import android.os.IBinder
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/** Both entry points are resolved reflectively by porsh copies in terminal apps and must stay resolvable. */
class ShellEntryPointsTest {
    @Test fun theLegacyEntryPointStays() {
        val method = Shell::class.java.getDeclaredMethod("main", Array<String>::class.java, String::class.java, IBinder::class.java, Handler::class.java)
        assertTrue(Modifier.isStatic(method.modifiers))
    }

    @Test fun theVersionedEntryPointExists() {
        val method = Shell::class.java.getDeclaredMethod("main", Array<String>::class.java, String::class.java, IBinder::class.java, Handler::class.java, Int::class.javaPrimitiveType)
        assertTrue(Modifier.isStatic(method.modifiers))
    }
}
