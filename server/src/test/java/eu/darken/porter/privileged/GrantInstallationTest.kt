package eu.darken.porter.privileged

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import eu.darken.porter.common.util.SignerDigests
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.MockedStatic
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import rikka.hidden.compat.PackageManagerApis
import rikka.shizuku.server.ConfigManager

/** A stored decision passes to a new process only for the installation it was made for. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class GrantInstallationTest {

    private class InMemoryConfig : ShizukuConfigManager() {
        override fun writeConfig(): Boolean = true
    }

    /** What the package manager answers for [PACKAGE], or null for nothing installed. */
    private var installed: PackageInfo? = null
    private var held: List<String> = listOf(PACKAGE)
    private lateinit var packages: MockedStatic<PackageManagerApis>
    private lateinit var config: InMemoryConfig

    private fun installation(signer: String): PackageInfo {
        val signingInfo = mock(SigningInfo::class.java)
        `when`(signingInfo.apkContentsSigners).thenReturn(arrayOf(Signature(signer)))
        return PackageInfo().apply {
            packageName = PACKAGE
            applicationInfo = ApplicationInfo().apply {
                uid = UID
                flags = ApplicationInfo.FLAG_INSTALLED
            }
            this.signingInfo = signingInfo
        }
    }

    @Before
    fun setup() {
        packages = mockStatic(PackageManagerApis::class.java)
        packages.`when`<List<String>> { PackageManagerApis.getPackagesForUidNoThrow(anyInt()) }.thenAnswer { held }
        packages.`when`<PackageInfo?> { PackageManagerApis.getPackageInfo(anyString(), anyLong(), anyInt()) }.thenAnswer { installed }
        config = InMemoryConfig()
    }

    @After
    fun teardown() {
        packages.close()
    }

    private fun allowNow() = config.update(UID, listOf(PACKAGE), ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_ALLOWED)

    @Test
    fun aGrantRecordsItsSignerAndPassesToTheSameInstallation() {
        installed = installation("aabb")
        allowNow()

        assertEquals(listOf(SignerDigests.of(Signature("aabb"))), config.find(UID)!!.signers)
        assertTrue(config.verifiedForAttach(UID, PACKAGE))
    }

    @Test
    fun anotherSignerOnTheSameUidAndNameIsNotGrantedAndTheDecisionIsDropped() {
        installed = installation("aabb")
        allowNow()

        installed = installation("ccdd")

        assertFalse(config.verifiedForAttach(UID, PACKAGE))
        assertNull(config.find(UID))
    }

    @Test
    fun aUidThatNoLongerHoldsTheDecidedPackageLosesTheDecision() {
        installed = installation("aabb")
        allowNow()

        held = listOf("com.newcomer")

        assertFalse(config.verifiedForAttach(UID, "com.newcomer"))
        assertNull(config.find(UID))
    }

    @Test
    fun anOlderDecisionLearnsItsSignerFromTheNextAttach() {
        installed = null
        allowNow()
        assertNull(config.find(UID)!!.signers)

        installed = installation("aabb")

        assertTrue(config.verifiedForAttach(UID, PACKAGE))
        assertEquals(listOf(SignerDigests.of(Signature("aabb"))), config.find(UID)!!.signers)
    }

    @Test
    fun anUnreadableInstallationIsNotGrantedButKeepsItsDecision() {
        installed = installation("aabb")
        allowNow()

        installed = null

        assertFalse(config.verifiedForAttach(UID, PACKAGE))
        assertNotNull(config.find(UID))
    }

    private companion object {
        const val UID = 10123
        const val PACKAGE = "com.example.client"
    }
}
