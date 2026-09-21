package eu.darken.porter.privileged

import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import moe.shizuku.server.IShizukuServiceConnection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.eq
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import rikka.hidden.compat.PackageManagerApis
import rikka.shizuku.ShizukuApiConstants
import rikka.shizuku.server.UserServiceRecord
import rikka.shizuku.server.util.HandlerUtil

/**
 * A differently signed APK installed over the same package name satisfies the package-name ownership
 * check on its own, so without an identity comparison it inherited the live privileged daemon the
 * original signer's app had started. Both hand-over paths are covered: reuse, and the noCreate
 * branch, which broadcasts the existing binder without ever creating a record.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class UserServiceBindIdentityTest {

    private lateinit var manager: ShizukuUserServiceManager
    private lateinit var packages: MockedStatic<PackageManagerApis>

    @Before
    fun setup() {
        HandlerUtil.mainHandler = mock(Handler::class.java)
        packages = Mockito.mockStatic(PackageManagerApis::class.java)
        ShadowBinder.setCallingUid(UID)
        manager = ShizukuUserServiceManager()
        manager.setReconciler(mock(ApkReconciler::class.java))
        installedWith(ORIGINAL)
    }

    @After
    fun teardown() {
        packages.close()
        ShadowBinder.reset()
    }

    private fun installedWith(vararg signers: Signature) {
        val packageInfo = PackageInfo()
        packageInfo.packageName = PACKAGE
        packageInfo.applicationInfo = ApplicationInfo().also {
            it.uid = UID
            it.flags = ApplicationInfo.FLAG_INSTALLED
            it.sourceDir = "/data/app/probe/base.apk"
        }
        val signingInfo = mock(SigningInfo::class.java)
        `when`(signingInfo.hasMultipleSigners()).thenReturn(signers.size > 1)
        `when`(signingInfo.apkContentsSigners).thenReturn(arrayOf(*signers))
        `when`(signingInfo.signingCertificateHistory).thenReturn(if (signers.size > 1) null else arrayOf(*signers))
        packageInfo.signingInfo = signingInfo
        packages.`when`<PackageInfo> { PackageManagerApis.getPackageInfoNoThrow(eq(PACKAGE), anyLong(), anyInt()) }
            .thenReturn(packageInfo)
    }

    /** Signatures the package manager refuses to hand back at all. */
    private fun installedWithUnreadableIdentity() {
        val packageInfo = PackageInfo()
        packageInfo.packageName = PACKAGE
        packageInfo.applicationInfo = ApplicationInfo().also {
            it.uid = UID
            it.flags = ApplicationInfo.FLAG_INSTALLED
            it.sourceDir = "/data/app/probe/base.apk"
        }
        packages.`when`<PackageInfo> { PackageManagerApis.getPackageInfoNoThrow(eq(PACKAGE), anyLong(), anyInt()) }
            .thenReturn(packageInfo)
    }

    private fun bind(noCreate: Boolean): Int =
        manager.addUserService(connection(), options(noCreate), ShizukuApiConstants.SERVER_VERSION)

    /** Brings a record all the way up, with a live binder, the way a real launch would. */
    private fun established(): UserServiceRecord {
        bind(false)
        val record = manager.snapshotHosts()[0].record
        val binder = mock(IBinder::class.java)
        `when`(binder.pingBinder()).thenReturn(true)
        `when`(binder.interfaceDescriptor).thenReturn("eu.darken.porter.probe.IProbe")
        `when`(binder.transact(anyInt(), any(), any(), anyInt())).thenReturn(true)
        val attach = Bundle()
        attach.putString(ShizukuApiConstants.USER_SERVICE_ARG_TOKEN, record.token)
        manager.attachUserService(binder, attach)
        return record
    }

    @Test
    fun theSameInstallationKeepsItsService() {
        val record = established()

        assertEquals(1, bind(true))
        assertSame(record, manager.snapshotHosts()[0].record)
        assertTrue(manager.isUserServiceTokenLive(record.token))
    }

    @Test
    fun aDifferentSignerDoesNotReceiveTheOldBinderOnTheReusePath() {
        val record = established()
        installedWith(FOREIGN)

        bind(false)

        assertTrue(record.isRemoved)
        val replacement = manager.snapshotHosts()[0].record
        assertNotEquals(record.token, replacement.token)
        assertNull(replacement.service)
    }

    @Test
    fun aDifferentSignerDoesNotReceiveTheOldBinderOnTheNoCreatePath() {
        val record = established()
        installedWith(FOREIGN)

        // -1 is "no service for you"; the old daemon's binder is never broadcast.
        assertEquals(-1, bind(true))
        assertTrue(record.isRemoved)
        assertEquals(0, manager.snapshotHosts().size)
    }

    @Test
    fun anUnreadableIdentityDefersRatherThanReusing() {
        val record = established()
        installedWithUnreadableIdentity()

        assertEquals(-1, bind(true))
        assertTrue(record.isRemoved)
    }

    @Test
    fun theIdentityComesFromTheLookupThatAuthorisedTheBind() {
        bind(false)
        val snapshot = manager.snapshotHosts()[0]

        assertEquals(PACKAGE, snapshot.packageName)
        assertEquals(UID, snapshot.identity.appId)
        assertEquals(1, snapshot.identity.signerDigests.size)
        // Only one lookup: the hook is handed the PackageInfo that authorised the bind.
        packages.verify { PackageManagerApis.getPackageInfoNoThrow(eq(PACKAGE), anyLong(), anyInt()) }
    }

    @Test
    fun pausingAccessRemovesEveryRecordedHost() {
        val record = established()

        manager.setAccessPaused(true)

        assertTrue(record.isRemoved)
        assertEquals(0, manager.snapshotHosts().size)
    }

    @Test
    fun aRemovedRecordLeavesTheHostMap() {
        val record = established()
        assertNotNull(manager.snapshotHosts()[0])

        record.removeSelf()

        assertEquals(0, manager.snapshotHosts().size)
        assertFalse(manager.removeIfPresent(record))
    }

    private companion object {
        const val PACKAGE = "eu.darken.porter.probe"
        const val CLASS = "ProbeService"
        const val UID = 10123

        val ORIGINAL = Signature("0a0b")
        val FOREIGN = Signature("0e0f")

        fun connection(): IShizukuServiceConnection = object : IShizukuServiceConnection.Stub() {
            override fun connected(service: IBinder?) {}
            override fun died() {}
        }

        fun options(noCreate: Boolean): Bundle = Bundle().apply {
            putParcelable(ShizukuApiConstants.USER_SERVICE_ARG_COMPONENT, ComponentName(PACKAGE, CLASS))
            putInt(ShizukuApiConstants.USER_SERVICE_ARG_VERSION_CODE, 1)
            putBoolean(ShizukuApiConstants.USER_SERVICE_ARG_DAEMON, true)
            putBoolean(ShizukuApiConstants.USER_SERVICE_ARG_NO_CREATE, noCreate)
        }
    }
}
