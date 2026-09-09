package moe.shizuku.manager.compatibility

import eu.darken.porter.common.CompatibilitySetup
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class CompatibilityImportStoreTest {
    @get:Rule val directory = TemporaryFolder()
    private val decisions = JSONArray("""[{"uid":10123,"packageName":"test.client","flags":2,"certificate":"abc"}]""")

    @Test fun reviewedImportSurvivesRecreationAndReadsUntilExplicitlyDiscarded() {
        CompatibilityImportStore(directory.root).save(decisions)
        val resumed = CompatibilityImportStore(directory.root)
        assertTrue(resumed.exists)
        assertEquals(decisions.toString(), resumed.read())
        assertEquals(decisions.toString(), CompatibilityImportStore(directory.root).read())
        resumed.discard()
        assertFalse(CompatibilityImportStore(directory.root).exists)
    }

    @Test fun anotherReplacementCannotOverwriteOutstandingImport() {
        val store = CompatibilityImportStore(directory.root)
        store.save(decisions)
        assertThrows(IllegalStateException::class.java) { store.save(JSONArray()) }
        assertEquals(decisions.toString(), store.read())
    }

    @Test fun interruptedAtomicWriteStillCountsAsOutstandingImport() {
        val store = CompatibilityImportStore(directory.root)
        store.save(decisions)
        val base = File(directory.root, "compatibility-import.json")
        assertTrue(base.renameTo(File(base.path + ".bak")))
        assertTrue(store.exists)
        assertThrows(IllegalStateException::class.java) { store.save(JSONArray()) }
        assertEquals(decisions.toString(), store.read())
    }

    @Test fun invalidOrOversizedSavedDataIsRetainedForExplicitDiscard() {
        val base = File(directory.root, "compatibility-import.json")
        base.writeText("""{"version":99,"decisions":[]}""")
        val store = CompatibilityImportStore(directory.root)
        assertThrows(IllegalStateException::class.java) { store.read() }
        assertTrue(store.exists)
        base.writeBytes(ByteArray(CompatibilitySetup.MAX_SNAPSHOT_BYTES + 1) { 32 })
        assertThrows(IllegalStateException::class.java) { store.read() }
        assertTrue(store.exists)
        store.discard()
        assertFalse(store.exists)
    }
}
