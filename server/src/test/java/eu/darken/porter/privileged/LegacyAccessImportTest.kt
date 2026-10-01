package eu.darken.porter.privileged

import eu.darken.porter.privileged.LegacyAccessImport.SkipReason
import eu.darken.porter.protocol.PorterProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyAccessImportTest {
    private val app = LegacyAccessImport.App()
    private var existing = false
    private var resolved = 0
    private var failure: Exception? = null
    private val resolver = object : LegacyAccessImport.Resolver {
        override fun resolve(packageName: String): LegacyAccessImport.App? {
            resolved++
            failure?.let { throw it }
            return if ("example.client" == packageName) app else null
        }

        override fun hasPorterDecision(uid: Int): Boolean = existing
    }

    private data class Skip(val uid: Int, val packageName: String?, val reason: SkipReason)

    private val skips = ArrayList<Skip>()

    init {
        app.packageName = "example.client"
        app.label = "Example"
        app.uid = 10123
        app.certificate = "current-cert"
        app.exclusiveUid = true
        app.legacy = true
        app.granted = true
    }

    private fun entry(uid: Int, flags: Int, packageName: String = "example.client"): String =
        "{\"uid\":$uid,\"flags\":$flags,\"packages\":[\"$packageName\"]}"

    private fun source(entries: String): String = "{\"version\":2,\"packages\":[$entries]}"

    private fun preview(entries: String): List<LegacyAccessImport.Decision> {
        skips.clear()
        return LegacyAccessImport.preview(source(entries), resolver) { uid, packageName, reason -> skips.add(Skip(uid, packageName, reason)) }
    }

    private fun assertSkipped(entries: String, vararg expected: Skip) {
        assertTrue(preview(entries).isEmpty())
        assertEquals(expected.toList(), skips)
    }

    private fun skip(reason: SkipReason, uid: Int = 10123, packageName: String? = "example.client") = Skip(uid, packageName, reason)

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

    @Test
    fun keptEntriesAreNotReported() {
        assertEquals(1, preview(entry(10123, 2)).size)
        assertTrue(skips.isEmpty())
    }

    @Test
    fun entryChecksReportTheirReason() {
        assertSkipped("null", skip(SkipReason.NULL_ENTRY, uid = -1, packageName = null))
        assertSkipped(
            entry(9999, 2) + "," + entry(100000, 2),
            skip(SkipReason.UID_OUT_OF_RANGE, uid = 9999),
            skip(SkipReason.UID_OUT_OF_RANGE, uid = 100000),
        )
        assertSkipped(
            entry(10123, 2) + "," + entry(10123, 4),
            skip(SkipReason.DUPLICATE_UID),
            skip(SkipReason.DUPLICATE_UID),
        )
        assertSkipped(
            "{\"uid\":10123,\"flags\":2}," +
                "{\"uid\":10124,\"flags\":2,\"packages\":[]}," +
                "{\"uid\":10125,\"flags\":2,\"packages\":[\"example.client\",\"example.other\"]}",
            skip(SkipReason.PACKAGES_NOT_SINGLE, packageName = null),
            skip(SkipReason.PACKAGES_NOT_SINGLE, uid = 10124, packageName = null),
            skip(SkipReason.PACKAGES_NOT_SINGLE, uid = 10125, packageName = null),
        )
        assertSkipped(entry(10123, 6), skip(SkipReason.UNSUPPORTED_FLAGS))
        assertSkipped(
            entry(10123, 2, PorterProtocol.MANAGER_APPLICATION_ID) + "," +
                entry(10124, 2, ServerConstants.COMPAT_APPLICATION_ID) + "," +
                entry(10125, 2, ""),
            skip(SkipReason.NOT_CLIENT_PACKAGE, packageName = PorterProtocol.MANAGER_APPLICATION_ID),
            skip(SkipReason.NOT_CLIENT_PACKAGE, uid = 10124, packageName = ServerConstants.COMPAT_APPLICATION_ID),
            skip(SkipReason.NOT_CLIENT_PACKAGE, uid = 10125, packageName = ""),
        )
        assertEquals(0, resolved)
        assertSkipped(entry(10123, 2, "other.client"), skip(SkipReason.PACKAGE_UNRESOLVED, packageName = "other.client"))
    }

    @Test
    fun existingPorterDecisionIsReportedWithoutResolving() {
        existing = true
        assertSkipped(entry(10123, 2), skip(SkipReason.PORTER_DECISION_EXISTS))
        assertEquals(0, resolved)
    }

    @Test
    fun resolvedAppChecksReportTheirReason() {
        app.uid = 10124
        assertSkipped(entry(10123, 2), skip(SkipReason.UID_CHANGED))
        app.uid = 10123
        app.exclusiveUid = false
        assertSkipped(entry(10123, 2), skip(SkipReason.UID_NOT_EXCLUSIVE))
        app.exclusiveUid = true
        app.legacy = false
        assertSkipped(entry(10123, 2), skip(SkipReason.NO_API_V23_REQUEST))
        app.legacy = true
        app.certificate = null
        assertSkipped(entry(10123, 2), skip(SkipReason.NO_CERTIFICATE))
        app.certificate = "current-cert"
        app.granted = false
        assertSkipped(entry(10123, 2), skip(SkipReason.ALLOW_NOT_GRANTED))
        app.granted = true
        assertSkipped(entry(10123, 4), skip(SkipReason.DENY_GRANTED))
    }

    @Test
    fun overlappingConditionsReportTheFirstCheck() {
        assertSkipped(
            "{\"uid\":10123,\"flags\":2}," + entry(10123, 2),
            skip(SkipReason.DUPLICATE_UID, packageName = null),
            skip(SkipReason.DUPLICATE_UID),
        )
        app.uid = 10124
        app.granted = false
        assertSkipped(entry(10123, 2), skip(SkipReason.UID_CHANGED))
    }

    @Test
    fun resolverFailurePropagatesWithoutReportingItsEntry() {
        val failure = IllegalStateException("Missing signing certificate")
        this.failure = failure
        val thrown = assertThrows(IllegalStateException::class.java) { preview("null," + entry(10123, 2)) }
        assertTrue(thrown === failure)
        assertEquals(listOf(skip(SkipReason.NULL_ENTRY, uid = -1, packageName = null)), skips)
    }

    @Test
    fun everyEntryIsEitherKeptOrReported() {
        val entries = listOf("null", entry(10123, 2), entry(10200, 2, "other.client"), entry(50, 2), entry(10300, 6))
        val decisions = preview(entries.joinToString(","))
        assertEquals(1, decisions.size)
        assertEquals(entries.size, skips.size + decisions.size)
    }
}
