package moe.shizuku.manager.management

import eu.darken.porter.common.DiscoveredApplication as Entry
import org.junit.Assert.*
import org.junit.Test

class DiscoveryUiStateTest {
    private fun app(status: Int, authorization: Int = Entry.DEFAULT) = AppsViewModel.App(
        "example", 10123, "Example", null, authorization, status, Entry.API_SHIZUKU, false, null)

    @Test fun missingCompanionCannotBeEnabledButAnExistingGrantCanBeRevoked() {
        assertFalse(app(Entry.NEEDS_COMPANION).canToggle)
        val granted = app(Entry.NEEDS_COMPANION, Entry.ALLOWED)
        assertTrue(granted.canToggle)
        assertFalse(granted.canAuthorize)
    }
    @Test fun permissionDeclarationDoesNotMakeUnsupportedClientActionable() {
        assertFalse(app(Entry.UNSUPPORTED).canAuthorize)
        assertFalse(app(Entry.UNSUPPORTED).canToggle)
    }
    @Test fun countsDistinguishGrantedCompatibleAndCompanionRequiredClients() {
        val state = AppsViewModel.State(listOf(app(Entry.DIRECT, Entry.ALLOWED),
            app(Entry.COMPANION), app(Entry.NEEDS_COMPANION), app(Entry.MANAGED_ONLY, Entry.ALLOWED)))
        assertEquals(2, state.grantedCount)
        assertEquals(2, state.compatibleCount)
        assertEquals(1, state.companionRequiredCount)
        assertFalse(state.allGranted)
    }
}
