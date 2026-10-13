package eu.darken.porter.privileged

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.content.pm.UserInfo
import android.os.Bundle
import android.util.Log
import eu.darken.porter.common.CompatibilitySetup
import eu.darken.porter.privileged.util.userInfos
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
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
import org.robolectric.shadows.ShadowLog
import rikka.hidden.compat.PackageManagerApis
import rikka.hidden.compat.PermissionManagerApis
import rikka.hidden.compat.UserManagerApis
import rikka.shizuku.server.util.Logger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class CompatibilitySetupHandlerTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun inspectionIncludesOtherInstalledUsersAndFailsWhenUsersUnknown() {
        // Android17Compat and Compatibility are Kotlin objects, which a static mock cannot intercept,
        // so the stubs sit one level below them on the hidden-API calls the compat layer makes and
        // Compatibility.isAvailable() runs for real over those answers.
        mockStatic(UserManagerApis::class.java).use { users ->
            mockStatic(PackageManagerApis::class.java).use { packages ->
                users.`when`<List<UserInfo>> { UserManagerApis.getUsers(true, true, true) }.thenReturn(userInfos(0, 10))
                val installed = PackageInfo()
                val applicationInfo = ApplicationInfo()
                applicationInfo.flags = ApplicationInfo.FLAG_INSTALLED
                installed.applicationInfo = applicationInfo
                packages.`when`<PackageInfo?> { PackageManagerApis.getPackageInfo(anyString(), anyLong(), anyInt()) }.thenAnswer { invocation ->
                    if (invocation.getArgument<String>(0) == ServerConstants.COMPAT_APPLICATION_ID && invocation.getArgument<Int>(2) == 10) installed else null
                }
                val handler = CompatibilitySetupHandler(mock(ShizukuConfigManager::class.java), { _, _ -> }, {})
                val reply = handler.execute(CompatibilitySetup.INSPECT, null)
                assertArrayEquals(intArrayOf(10), reply.getIntArray("users"))
                assertArrayEquals(intArrayOf(10), reply.getIntArray("companionUsers"))
                assertNull(reply.getString("certificate"))
                users.`when`<List<UserInfo>> { UserManagerApis.getUsers(true, true, true) }.thenReturn(emptyList())
                assertThrows(IllegalStateException::class.java) { handler.execute(CompatibilitySetup.INSPECT, null) }
            }
        }
    }

    @Test
    fun inspectionOfAnotherPackageReportsItsUsersAndCertificateBesideTheCompanionUsers() {
        mockStatic(UserManagerApis::class.java).use { users ->
            mockStatic(PackageManagerApis::class.java).use { packages ->
                users.`when`<List<UserInfo>> { UserManagerApis.getUsers(true, true, true) }.thenReturn(userInfos(0, 10))
                val companion = signed(ServerConstants.COMPAT_APPLICATION_ID, Signature(byteArrayOf(1, 2, 3)))
                val targetSignature = Signature(byteArrayOf(4, 5, 6))
                val target = signed(FOREIGN_OWNER, targetSignature)
                var targetUser = 10
                packages.`when`<PackageInfo?> { PackageManagerApis.getPackageInfo(anyString(), anyLong(), anyInt()) }.thenAnswer { invocation ->
                    val user = invocation.getArgument<Int>(2)
                    when (invocation.getArgument<String>(0)) {
                        ServerConstants.COMPAT_APPLICATION_ID -> if (user == 0) companion else null
                        FOREIGN_OWNER -> if (user == targetUser) target else null
                        else -> null
                    }
                }
                val handler = CompatibilitySetupHandler(mock(ShizukuConfigManager::class.java), { _, _ -> }, {})
                val reply = handler.execute(CompatibilitySetup.INSPECT, FOREIGN_OWNER)
                assertArrayEquals(intArrayOf(10), reply.getIntArray("users"))
                assertArrayEquals(intArrayOf(0), reply.getIntArray("companionUsers"))
                assertNull(reply.getString("certificate"))

                targetUser = 0
                val owned = handler.execute(CompatibilitySetup.INSPECT, FOREIGN_OWNER)
                assertArrayEquals(intArrayOf(0), owned.getIntArray("users"))
                assertEquals(certificate(targetSignature), owned.getString("certificate"))
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
                    decision.certificate = certificate(signature)
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

    @Test
    fun previewLogsLeftOutEntriesAndTheDatabaseItRead() {
        val database = temporary.newFile("shizuku.json")
        database.writeText(DATABASE)
        val (reply, logs) = preview(database)
        assertEquals(1, LegacyAccessImport.decode(reply.getString("decisions")!!).size)
        assertEquals(
            listOf(
                Log.INFO to "Import preview leaves out uid=10200 reason=PACKAGE_UNRESOLVED package=missing.client",
                Log.INFO to "Import preview read shizuku.json: entries=2 kept=1",
            ),
            logs.map { it.type to it.msg },
        )
    }

    @Test
    fun previewReadsAndNamesTheBackupWhenPresent() {
        val database = temporary.newFile("shizuku.json")
        database.writeText("{\"version\":2,\"packages\":[]}")
        File(database.path + ".bak").writeText(DATABASE)
        val (_, logs) = preview(database)
        assertEquals(Log.INFO to "Import preview read shizuku.json.bak: entries=2 kept=1", logs.last().let { it.type to it.msg })
    }

    @Test
    fun previewOfAnotherPackageOwningThePermissionReadsTheDatabase() {
        val database = temporary.newFile("shizuku.json")
        database.writeText(DATABASE)
        val (reply, logs) = preview(database, FOREIGN_OWNER)
        assertEquals(1, LegacyAccessImport.decode(reply.getString("decisions")!!).size)
        assertEquals(Log.INFO to "Import preview read shizuku.json: entries=2 kept=1", logs.last().let { it.type to it.msg })
    }

    @Test
    fun previewOfAnotherPackageNotOwningThePermissionAtDangerousLevelFailsBeforeReadingTheDatabase() {
        val database = temporary.newFile("shizuku.json")
        database.writeText(DATABASE)
        for (protection in listOf(null, PermissionInfo.PROTECTION_SIGNATURE)) {
            val error = assertThrows(IllegalStateException::class.java) { preview(database, FOREIGN_OWNER, protection) }
            assertEquals("Installed app does not own the Shizuku permission", error.message)
            assertEquals(emptyList<ShadowLog.LogItem>(), ShadowLog.getLogsForTag("CompatibilityImport"))
        }
    }

    /**
     * Runs PREVIEW_IMPORT for [target] (the original Shizuku when null) with Porter's companion absent.
     * The target defines the legacy permission at [protection], or not at all when that is null.
     */
    private fun preview(
        database: File,
        target: String? = null,
        protection: Int? = PermissionInfo.PROTECTION_DANGEROUS,
    ): Pair<Bundle, List<ShadowLog.LogItem>> =
        mockStatic(PackageManagerApis::class.java).use { packages ->
            mockStatic(PermissionManagerApis::class.java).use { permissions ->
                val owner = target ?: ServerConstants.COMPAT_APPLICATION_ID
                val signature = Signature(byteArrayOf(1, 2, 3))
                val original = signed(owner, signature)
                if (protection == null) {
                    original.permissions = arrayOf()
                } else {
                    val legacy = PermissionInfo()
                    legacy.name = ServerConstants.LEGACY_PERMISSION
                    legacy.packageName = owner
                    legacy.protectionLevel = protection
                    original.permissions = arrayOf(legacy)
                }
                val installed = signed("test.client", signature)
                installed.applicationInfo!!.uid = 10123
                installed.requestedPermissions = arrayOf(ServerConstants.LEGACY_PERMISSION)
                packages.`when`<PackageInfo?> { PackageManagerApis.getPackageInfo(anyString(), anyLong(), anyInt()) }.thenAnswer { invocation ->
                    if (invocation.getArgument<Int>(2) != 0) return@thenAnswer null
                    when (invocation.getArgument<String>(0)) {
                        "test.client" -> installed
                        owner -> original
                        else -> null
                    }
                }
                packages.`when`<List<String>> { PackageManagerApis.getPackagesForUidNoThrow(10123) }.thenReturn(listOf("test.client"))
                permissions.`when`<Int> { PermissionManagerApis.checkPermission(anyString(), anyString(), anyInt()) }.thenReturn(PackageManager.PERMISSION_GRANTED)
                val handler = CompatibilitySetupHandler(mock(ShizukuConfigManager::class.java), { _, _ -> }, {}, database)
                ShadowLog.clear()
                Logger.setDebugAlways(false)
                try {
                    val reply = handler.execute(CompatibilitySetup.PREVIEW_IMPORT, target)
                    reply to ShadowLog.getLogsForTag("CompatibilityImport")
                } finally {
                    Logger.setDebugAlways(true)
                }
            }
        }

    private companion object {
        const val FOREIGN_OWNER = "kerneldroid.nightzuku"

        const val DATABASE = "{\"version\":2,\"packages\":[" +
            "{\"uid\":10123,\"flags\":2,\"packages\":[\"test.client\"]}," +
            "{\"uid\":10200,\"flags\":2,\"packages\":[\"missing.client\"]}]}"

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

        fun certificate(signature: Signature): String {
            val digest = StringBuilder()
            for (b in MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())) digest.append(String.format("%02x", b.toInt() and 255))
            return digest.toString()
        }
    }
}
