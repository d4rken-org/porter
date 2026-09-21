package eu.darken.porter.privileged

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import eu.darken.porter.common.CompatibilitySetup
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import rikka.hidden.compat.PackageManagerApis
import rikka.hidden.compat.PermissionManagerApis
import rikka.hidden.compat.UserManagerApis

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class CompatibilitySetupHandlerTest {

    @Test
    fun inspectionIncludesOtherInstalledUsersAndFailsWhenUsersUnknown() {
        // Android17Compat and Compatibility are Kotlin objects, which a static mock cannot intercept,
        // so the stubs sit one level below them on the hidden-API calls the compat layer makes and
        // Compatibility.isAvailable() runs for real over those answers.
        mockStatic(UserManagerApis::class.java).use { users ->
            mockStatic(PackageManagerApis::class.java).use { packages ->
                users.`when`<List<Int>> { UserManagerApis.getUserIdsNoThrow() }.thenReturn(listOf(0, 10))
                val installed = PackageInfo()
                val applicationInfo = ApplicationInfo()
                applicationInfo.flags = ApplicationInfo.FLAG_INSTALLED
                installed.applicationInfo = applicationInfo
                packages.`when`<PackageInfo?> { PackageManagerApis.getPackageInfo(anyString(), anyLong(), anyInt()) }.thenAnswer { invocation ->
                    if (invocation.getArgument<String>(0) == ServerConstants.COMPAT_APPLICATION_ID && invocation.getArgument<Int>(2) == 10) installed else null
                }
                val handler = CompatibilitySetupHandler(mock(ShizukuConfigManager::class.java), { _, _ -> }, {})
                assertArrayEquals(intArrayOf(10), handler.execute(CompatibilitySetup.INSPECT, null).getIntArray("users"))
                users.`when`<List<Int>> { UserManagerApis.getUserIdsNoThrow() }.thenReturn(emptyList())
                assertThrows(IllegalStateException::class.java) { handler.execute(CompatibilitySetup.INSPECT, null) }
            }
        }
    }

    @Test
    fun failedDecisionOrDurableSaveCannotReturnSuccessfulImport() {
        mockStatic(PackageManagerApis::class.java).use { packages ->
            mockStatic(PermissionManagerApis::class.java).use { permissions ->
                run {
                    // Compatibility.isAvailable() runs for real: a manager and a companion signed alike,
                    // the companion owning the legacy permission at dangerous level.
                    val signature = Signature(byteArrayOf(1, 2, 3))
                    val manager = signed(PorterServer.MANAGER_APPLICATION_ID, signature)
                    val companion = signed(ServerConstants.COMPAT_APPLICATION_ID, signature)
                    val legacy = PermissionInfo()
                    legacy.name = ServerConstants.LEGACY_PERMISSION
                    legacy.packageName = ServerConstants.COMPAT_APPLICATION_ID
                    legacy.protectionLevel = PermissionInfo.PROTECTION_DANGEROUS
                    companion.permissions = arrayOf(legacy)
                    val installed = signed("test.client", signature)
                    installed.applicationInfo!!.uid = 10123
                    installed.requestedPermissions = arrayOf(ServerConstants.LEGACY_PERMISSION)
                    packages.`when`<PackageInfo?> { PackageManagerApis.getPackageInfo(anyString(), anyLong(), anyInt()) }.thenAnswer { invocation ->
                        if (invocation.getArgument<Int>(2) != 0) return@thenAnswer null
                        when (invocation.getArgument<String>(0)) {
                            "test.client" -> installed
                            PorterServer.MANAGER_APPLICATION_ID -> manager
                            ServerConstants.COMPAT_APPLICATION_ID -> companion
                            else -> null
                        }
                    }
                    packages.`when`<List<String>> { PackageManagerApis.getPackagesForUidNoThrow(10123) }.thenReturn(listOf("test.client"))
                    permissions.`when`<Int> { PermissionManagerApis.checkPermission(anyString(), anyString(), anyInt()) }.thenReturn(PackageManager.PERMISSION_GRANTED)
                    val decision = LegacyAccessImport.Decision()
                    decision.packageName = "test.client"
                    decision.uid = 10123
                    decision.flags = LegacyAccessImport.ALLOW
                    val digest = StringBuilder()
                    for (b in MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())) digest.append(String.format("%02x", b.toInt() and 255))
                    decision.certificate = digest.toString()
                    val json = LegacyAccessImport.encode(listOf(decision))
                    val config = mock(ShizukuConfigManager::class.java)
                    val refresh = mock(Runnable::class.java)
                    val failingWriter = CompatibilitySetupHandler(config, { _, _ -> throw IllegalStateException("Package changed") }, refresh)
                    assertThrows(IllegalStateException::class.java) { failingWriter.execute(CompatibilitySetup.APPLY_IMPORT, json) }
                    verify(config, never()).persistImport()
                    verifyNoInteractions(refresh)

                    doThrow(IllegalStateException("Disk unavailable")).doNothing().`when`(config).persistImport()
                    val handler = CompatibilitySetupHandler(config, { _, _ -> }, refresh)
                    assertThrows(IllegalStateException::class.java) { handler.execute(CompatibilitySetup.APPLY_IMPORT, json) }
                    verifyNoInteractions(refresh)
                    assertEquals(1, handler.execute(CompatibilitySetup.APPLY_IMPORT, json).getInt("applied"))
                    verify(refresh).run()
                }
            }
        }
    }

    private companion object {
        /** A package installed for user 0 with one signer, as the API 34 signing info carries it. */
        fun signed(packageName: String, vararg signers: Signature): PackageInfo {
            val info = PackageInfo()
            info.packageName = packageName
            val applicationInfo = ApplicationInfo()
            applicationInfo.flags = ApplicationInfo.FLAG_INSTALLED
            info.applicationInfo = applicationInfo
            val signingInfo = mock(SigningInfo::class.java)
            `when`(signingInfo.apkContentsSigners).thenReturn(arrayOf(*signers))
            info.signingInfo = signingInfo
            return info
        }
    }
}
