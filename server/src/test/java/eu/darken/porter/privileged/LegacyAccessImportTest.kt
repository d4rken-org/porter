package eu.darken.porter.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyAccessImportTest {
    private val app = LegacyAccessImport.App()
    private var existing = false
    private val resolver = object : LegacyAccessImport.Resolver {
        override fun resolve(packageName: String): LegacyAccessImport.App? = if ("example.client" == packageName) app else null
        override fun hasPorterDecision(uid: Int): Boolean = existing
    }

    init {
        app.packageName = "example.client"
        app.label = "Example"
        app.uid = 10123
        app.certificate = "current-cert"
        app.exclusiveUid = true
        app.legacy = true
        app.granted = true
    }

    private fun entry(uid: Int, flags: Int): String =
        "{\"uid\":$uid,\"flags\":$flags,\"packages\":[\"example.client\"]}"

    private fun source(entries: String): String = "{\"version\":2,\"packages\":[$entries]}"

    @Test
    fun importCarriesIdentityAndDecision() {
        val decisions = LegacyAccessImport.preview(source(entry(10123, 2)), resolver)
        assertEquals(1, decisions.size)
        assertEquals("current-cert", decisions[0].certificate)
        assertTrue(LegacyAccessImport.canApply(LegacyAccessImport.decode(LegacyAccessImport.encode(decisions))[0], resolver))
    }

    @Test
    fun revokedLiveGrantIsNotResurrectedFromFile() {
        app.granted = false
        assertTrue(LegacyAccessImport.preview(source(entry(10123, 2)), resolver).isEmpty())
    }

    @Test
    fun explicitDenialCanBeReviewedButStaleDenialIsSkipped() {
        assertTrue(LegacyAccessImport.preview(source(entry(10123, 4)), resolver).isEmpty())
        app.granted = false
        assertEquals(4, LegacyAccessImport.preview(source(entry(10123, 4)), resolver)[0].flags)
    }

    @Test
    fun existingPorterDecisionAlwaysWinsIncludingDefaultEntry() {
        val decision = LegacyAccessImport.preview(source(entry(10123, 2)), resolver)[0]
        existing = true
        assertTrue(LegacyAccessImport.preview(source(entry(10123, 2)), resolver).isEmpty())
        assertFalse(LegacyAccessImport.canApply(decision, resolver))
    }

    @Test
    fun changedUidOrSignerAndSharedUidAreSkipped() {
        val decision = LegacyAccessImport.preview(source(entry(10123, 2)), resolver)[0]
        app.uid++
        assertFalse(LegacyAccessImport.canApply(decision, resolver))
        app.uid--
        app.certificate = "replacement-cert"
        assertFalse(LegacyAccessImport.canApply(decision, resolver))
        app.certificate = "current-cert"
        app.exclusiveUid = false
        assertFalse(LegacyAccessImport.canApply(decision, resolver))
        assertTrue(LegacyAccessImport.preview(source(entry(10123, 2)), resolver).isEmpty())
    }

    @Test
    fun duplicateUidAndConflictingFlagsAreNotImported() {
        assertTrue(LegacyAccessImport.preview(source(entry(10123, 2) + "," + entry(10123, 4)), resolver).isEmpty())
        assertTrue(LegacyAccessImport.preview(source(entry(10123, 6)), resolver).isEmpty())
    }

    @Test
    fun otherAndroidUsersAreNotImported() {
        app.uid = 110123
        assertTrue(LegacyAccessImport.preview(source(entry(110123, 2)), resolver).isEmpty())
    }

    @Test
    fun unsupportedAndMalformedSourcesFailBeforeReplacement() {
        assertThrows(Exception::class.java) { LegacyAccessImport.preview("{broken", resolver) }
        assertThrows(IllegalArgumentException::class.java) { LegacyAccessImport.preview("{\"version\":3,\"packages\":[]}", resolver) }
    }
}
