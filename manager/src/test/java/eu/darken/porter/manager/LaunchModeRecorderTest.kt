package eu.darken.porter.manager

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import eu.darken.porter.server.IPorterApplication
import eu.darken.porter.server.IPorterService
import eu.darken.porter.server.IPorterServiceConnection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Start on boot does nothing unless the launch method was recorded, so it has to be recorded from
 * the delivered binder rather than from a screen someone may never have stayed on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [34])
class LaunchModeRecorderTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()

    /** A server that answers for its uid, or refuses to. */
    private class FakeServer(private val uid: Int, private val answers: Boolean = true) : IPorterService.Stub() {
        override fun getUid(): Int = if (answers) uid else throw RuntimeException("The server is gone")
        override fun attach(application: IPorterApplication?, args: Bundle?): Bundle = Bundle()
        override fun checkPermission(permission: String?): Int = 0
        override fun getSELinuxContext(): String = ""
        override fun getSystemProperty(name: String?, defaultValue: String?): String = ""
        override fun setSystemProperty(name: String?, value: String?) = Unit
        override fun addUserService(conn: IPorterServiceConnection?, args: Bundle?): Int = 0
        override fun removeUserService(conn: IPorterServiceConnection?, args: Bundle?): Int = 0
        override fun requestPermission(requestCode: Int) = Unit
        override fun checkSelfPermission(): Boolean = false
        override fun shouldShowRequestPermissionRationale(): Boolean = false
    }

    @Before fun reset() {
        application.createDeviceProtectedStorageContext()
            .getSharedPreferences(PorterSettings.NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        PorterSettings.resetForTest()
        PorterSettings.initialize(application)
        forgetBinder()
    }

    @After fun forgetBinder() {
        ServerBinder.binder.value?.let { ServerBinder.drop(it) }
    }

    private fun deliver(server: FakeServer): FakeServer {
        ServerBinder.deliver(server)
        assertEquals(server, ServerBinder.binder.value)
        return server
    }

    @Test fun aServerRunningAsRootIsARootStart() {
        LaunchModeRecorder.record(deliver(FakeServer(uid = 0)))
        assertEquals(PorterSettings.LaunchMethod.ROOT, PorterSettings.lastLaunchMode)
    }

    @Test fun aServerRunningAsShellIsAnAdbStart() {
        LaunchModeRecorder.record(deliver(FakeServer(uid = 2000)))
        assertEquals(PorterSettings.LaunchMethod.ADB, PorterSettings.lastLaunchMode)
    }

    /** A server that cannot say what it is leaves the last working method alone. */
    @Test fun aServerThatDoesNotAnswerRecordsNothing() {
        PorterSettings.lastLaunchMode = PorterSettings.LaunchMethod.ROOT
        LaunchModeRecorder.record(deliver(FakeServer(uid = 2000, answers = false)))
        assertEquals(PorterSettings.LaunchMethod.ROOT, PorterSettings.lastLaunchMode)
    }

    /** The uid is resolved off the delivery, so a reply about a binder that has since been replaced is stale. */
    @Test fun aBinderThatIsNoLongerHeldRecordsNothing() {
        val replaced = FakeServer(uid = 0)
        deliver(FakeServer(uid = 2000))
        LaunchModeRecorder.record(replaced)
        assertEquals(PorterSettings.LaunchMethod.UNKNOWN, PorterSettings.lastLaunchMode)
    }
}
