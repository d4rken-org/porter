package eu.darken.porter.manager.support

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * What an attach does before the user is unlocked, which a direct-boot start reaches first and
 * which must happen exactly once however the unlock arrives.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DebugRecorderAttachTest {
    @get:Rule val temporary = TemporaryFolder()
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val users = shadowOf(application.getSystemService(UserManager::class.java))
    private val scope = TestScope(StandardTestDispatcher())
    private val store by lazy { CountingStore(temporary.newFolder()) }

    /** Counts what an initialization reaches on disk, and does none of it. */
    private class CountingStore(root: File) : DebugLogStore(root) {
        var reads = 0
            private set
        var prunes = 0
            private set

        override fun activeId(): String? {
            reads++
            return null
        }

        override fun prune() {
            prunes++
        }
    }

    @After fun dropTheInstance() {
        DebugRecorder.resetForTest()
    }

    private fun recorder(context: Context = application) = DebugRecorder(context, storeOverride = store, scope = scope)

    private fun drain() = scope.testScheduler.advanceUntilIdle()

    private fun unlockReceivers() = shadowOf(application).registeredReceivers
        .filter { it.intentFilter.hasAction(Intent.ACTION_USER_UNLOCKED) }

    /** Attaches while locked and hands back the receiver that is waiting for the unlock. */
    private fun deferredReceiver(): BroadcastReceiver {
        users.setUserUnlocked(false)
        recorder().attach()
        drain()
        return unlockReceivers().single().broadcastReceiver
    }

    @Test fun anUnlockedUserInitializesWithoutWaiting() {
        recorder().attach()
        drain()

        assertEquals(1, store.reads)
        assertEquals(1, store.prunes)
        assertTrue(unlockReceivers().isEmpty())
    }

    @Test fun aLockedUserWaitsForTheUnlock() {
        users.setUserUnlocked(false)

        recorder().attach()
        drain()

        assertEquals(1, unlockReceivers().size)
        assertEquals(0, store.reads)
        assertEquals(0, store.prunes)
    }

    @Test fun theUnlockInitializesOnceAndStopsListening() {
        deferredReceiver()

        users.setUserUnlocked(true)
        application.sendBroadcast(Intent(Intent.ACTION_USER_UNLOCKED))
        shadowOf(Looper.getMainLooper()).idle()
        drain()

        assertTrue(unlockReceivers().isEmpty())
        assertEquals(1, store.reads)
        assertEquals(1, store.prunes)
    }

    @Test fun aSecondDeliveryToTheSameReceiverInitializesNothing() {
        val receiver = deferredReceiver()
        users.setUserUnlocked(true)
        application.sendBroadcast(Intent(Intent.ACTION_USER_UNLOCKED))
        shadowOf(Looper.getMainLooper()).idle()
        drain()

        receiver.onReceive(application, Intent(Intent.ACTION_USER_UNLOCKED))
        drain()

        assertEquals(1, store.reads)
        assertEquals(1, store.prunes)
    }

    /**
     * A direct-boot start constructs the recorder while the user is locked. Resolving the log
     * directory there costs a mkdir attempt and a framework warning on credential-protected
     * storage; the lookup belongs to attach, which already waits for the unlock.
     */
    @Test fun constructionStaysOutOfCredentialProtectedStorage() {
        var lookups = 0
        val counting = object : ContextWrapper(application) {
            override fun getNoBackupFilesDir(): File {
                lookups++
                return super.getNoBackupFilesDir()
            }
        }

        val recorder = DebugRecorder(counting, scope = scope)

        assertEquals(0, lookups)

        recorder.attach()
        drain()

        assertTrue(lookups > 0)
    }

    @Test fun aUserUnlockedWhileRegisteringInitializesOnce() {
        users.setUserUnlocked(false)
        val unlockingContext = object : ContextWrapper(application) {
            override fun registerReceiver(
                receiver: BroadcastReceiver?,
                filter: IntentFilter?,
                broadcastPermission: String?,
                scheduler: Handler?,
                flags: Int,
            ): Intent? {
                users.setUserUnlocked(true)
                return super.registerReceiver(receiver, filter, broadcastPermission, scheduler, flags)
            }
        }

        recorder(unlockingContext).attach()
        drain()

        assertEquals(1, store.reads)
        assertEquals(1, store.prunes)
        assertTrue(unlockReceivers().isEmpty())
    }
}
